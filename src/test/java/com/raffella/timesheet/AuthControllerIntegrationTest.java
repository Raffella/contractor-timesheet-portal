package com.raffella.timesheet;

import java.nio.file.Files;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class AuthControllerIntegrationTest {

    @Autowired
    private AuthController auth;

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry properties) throws Exception {
        var folder = Files.createTempDirectory("auth-test");
        properties.add("portal.database-url",
                () -> "jdbc:sqlite:" + folder.resolve("portal.sqlite"));
    }

    @Test
    void acceptsValidOtpOnce(CapturedOutput output) throws Exception {
        String email = "alex@example.test";
        auth.requestCode(new AuthController.CodeRequest(email));

        var printedCode = Pattern.compile("LOCAL OTP for alex@example\\.test: (\\d{6})")
                .matcher(output.getOut());
        assertTrue(printedCode.find(), "Expected a demo OTP in the console");

        var credentials = new AuthController.VerifyRequest(email, printedCode.group(1));
        var firstRequest = new MockHttpServletRequest();

        assertEquals(HttpStatus.OK, auth.verifyCode(credentials, firstRequest).getStatusCode());
        var session = firstRequest.getSession(false);
        assertNotNull(session);
        assertEquals(email, session.getAttribute("contractorEmail"));

        var secondRequest = new MockHttpServletRequest();
        assertEquals(HttpStatus.BAD_REQUEST,
                auth.verifyCode(credentials, secondRequest).getStatusCode());
        assertNull(secondRequest.getSession(false));
    }
    @Test
    void rejectsAnExpiredOtp(CapturedOutput output, @Autowired Database database)
            throws Exception {
        String email = "maya@example.test";
        auth.requestCode(new AuthController.CodeRequest(email));

        var printedCode = Pattern.compile("LOCAL OTP for maya@example\\.test: (\\d{6})")
                .matcher(output.getOut());
        assertTrue(printedCode.find(), "Expected a demo OTP in the console");

        int updatedRows = database.write(connection -> Database.update(
                connection,
                "UPDATE contractor_login_codes SET expires_at = ? WHERE contractor_id = ?",
                java.time.Instant.now().minusSeconds(1).getEpochSecond(),
                2L
        ));
        assertEquals(1, updatedRows);

        var request = new MockHttpServletRequest();
        var credentials = new AuthController.VerifyRequest(email, printedCode.group(1));

        assertEquals(HttpStatus.BAD_REQUEST, auth.verifyCode(credentials, request).getStatusCode());
        assertNull(request.getSession(false));
    }
    @Test
    void locksOtpAfterFiveWrongAttempts(CapturedOutput output) throws Exception {
        String email = "jordan@example.test";
        auth.requestCode(new AuthController.CodeRequest(email));

        var printedCode = Pattern.compile("LOCAL OTP for jordan@example\\.test: (\\d{6})")
                .matcher(output.getOut());
        assertTrue(printedCode.find(), "Expected a demo OTP in the console");

        String correctCode = printedCode.group(1);
        String wrongCode = correctCode.equals("123456") ? "654321" : "123456";
        var wrongCredentials = new AuthController.VerifyRequest(email, wrongCode);

        for (int attempt = 1; attempt <= 5; attempt++) {
            var request = new MockHttpServletRequest();
            assertEquals(HttpStatus.BAD_REQUEST,
                    auth.verifyCode(wrongCredentials, request).getStatusCode());
            assertNull(request.getSession(false));
        }

        var finalRequest = new MockHttpServletRequest();
        var correctCredentials = new AuthController.VerifyRequest(email, correctCode);
        assertEquals(HttpStatus.BAD_REQUEST,
                auth.verifyCode(correctCredentials, finalRequest).getStatusCode());
        assertNull(finalRequest.getSession(false));
    }
    @Test
    void limitsOtpRequestsToFivePerHour(CapturedOutput output, @Autowired Database database)
            throws Exception {
        String email = "rate-limit@example.test";
        long contractorId = 99L;

        database.write(connection -> Database.update(
                connection,
                "INSERT INTO contractors (id, email, full_name) VALUES (?, ?, ?)",
                contractorId, email, "Rate Limit Test"
        ));

        var request = new AuthController.CodeRequest(email);
        for (int attempt = 1; attempt <= 6; attempt++) {
            auth.requestCode(request);
        }

        long printedCodes = Pattern.compile("LOCAL OTP for rate-limit@example\\.test: \\d{6}")
                .matcher(output.getOut())
                .results()
                .count();
        assertEquals(5L, printedCodes);

        var result = database.read(connection -> Database.one(
                connection,
                "SELECT COUNT(*) AS total FROM contractor_otp_requests WHERE contractor_id = ?",
                contractorId
        ));
        assertEquals(5L, Database.number(result, "total"));
    }
    @Test
    void signsOutAndRejectsFurtherSessionChecks() throws Exception {
        String email = "alex@example.test";
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute("contractorEmail", email);

        var beforeSignOut = auth.checkSession(request);
        assertEquals(HttpStatus.OK, beforeSignOut.getStatusCode());
        assertEquals(email, beforeSignOut.getBody().get("email"));

        auth.signOut(request);

        assertNull(request.getSession(false));
        assertEquals(HttpStatus.UNAUTHORIZED, auth.checkSession(request).getStatusCode());
    }
    @Test
    void doesNotIssueOtpForUnknownEmail(CapturedOutput output) throws Exception {
        String email = "unknown@example.test";
        auth.requestCode(new AuthController.CodeRequest(email));

        org.junit.jupiter.api.Assertions.assertFalse(
                output.getOut().contains("LOCAL OTP for " + email + ":"));
    }

    @Test
    void clearsSessionForUnknownContractor() throws Exception {
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute("contractorEmail", "unknown@example.test");

        assertEquals(HttpStatus.UNAUTHORIZED, auth.checkSession(request).getStatusCode());
        assertNull(request.getSession(false));
    }
}