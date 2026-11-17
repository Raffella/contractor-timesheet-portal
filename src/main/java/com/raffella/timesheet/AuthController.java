package com.raffella.timesheet;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final Database database;
    private final SecureRandom random = new SecureRandom();
    private final byte[] signingKey;

    public AuthController(Database database) {
        this.database = database;
        this.signingKey = loadSigningKey();
    }

    public record CodeRequest(String email) {}

    public record VerifyRequest(String email, String code) {}

    @PostMapping("/api/auth/request")
    public Map<String, String> requestCode(
            @RequestBody CodeRequest request) throws SQLException {

        String email = normalizeEmail(request.email());

        String code = database.write(connection -> {
            var contractor = Database.one(connection,
                    "SELECT id FROM contractors WHERE email = ?", email);

            if (contractor == null) {
                return null;
            }

            long contractorId = ((Number) contractor.get("id")).longValue();
            long now = Instant.now().getEpochSecond();

            var result = Database.one(connection, """
                    SELECT COUNT(*) AS total
                    FROM contractor_otp_requests
                    WHERE contractor_id = ? AND requested_at > ?
                    """, contractorId, now - 3600);

            if (((Number) result.get("total")).longValue() >= 5) {
                return null;
            }

            String newCode = String.valueOf(100_000 + random.nextInt(900_000));

            Database.update(connection, """
                    INSERT INTO contractor_login_codes
                        (contractor_id, code_hash, expires_at, failed_attempts)
                    VALUES (?, ?, ?, 0)
                    ON CONFLICT(contractor_id) DO UPDATE SET
                        code_hash = excluded.code_hash,
                        expires_at = excluded.expires_at,
                        failed_attempts = 0
                    """, contractorId, fingerprint(newCode), now + 600);

            Database.update(connection, """
                    INSERT INTO contractor_otp_requests (contractor_id, requested_at)
                    VALUES (?, ?)
                    """, contractorId, now);

            return newCode;
        });

        if (code != null) {
            // Local demo delivery. Read the code in IntelliJ's Run console.
            System.out.println("LOCAL OTP for " + email + ": " + code);
        }

        return Map.of("message", "If this email is registered, a code was sent.");
    }

    @PostMapping("/api/auth/verify")
    public ResponseEntity<Map<String, String>> verifyCode(
            @RequestBody VerifyRequest request,
            HttpServletRequest servletRequest) throws SQLException {

        String email = normalizeEmail(request.email());
        String code = request.code();

        if (code == null || !code.matches("[0-9]{6}")) {
            return invalidCode();
        }

        boolean verified = database.write(connection -> {
            var pending = Database.one(connection, """
                    SELECT c.id AS contractor_id, l.code_hash,
                           l.expires_at, l.failed_attempts
                    FROM contractors c
                    JOIN contractor_login_codes l ON l.contractor_id = c.id
                    WHERE c.email = ?
                    """, email);

            if (pending == null
                    || ((Number) pending.get("expires_at")).longValue()
                    <= Instant.now().getEpochSecond()
                    || ((Number) pending.get("failed_attempts")).intValue() >= 5) {
                return false;
            }

            long contractorId = ((Number) pending.get("contractor_id")).longValue();
            byte[] storedHash = (byte[]) pending.get("code_hash");

            if (!MessageDigest.isEqual(storedHash, fingerprint(code))) {
                Database.update(connection, """
                        UPDATE contractor_login_codes
                        SET failed_attempts = failed_attempts + 1
                        WHERE contractor_id = ?
                        """, contractorId);
                return false;
            }

            // Consuming the row makes a verified code single-use.
            Database.update(connection,
                    "DELETE FROM contractor_login_codes WHERE contractor_id = ?",
                    contractorId);
            return true;
        });

        if (!verified) {
            return invalidCode();
        }

        var previousSession = servletRequest.getSession(false);
        if (previousSession != null) {
            previousSession.invalidate();
        }

        var session = servletRequest.getSession();
        session.setAttribute("contractorEmail", email);
        session.setMaxInactiveInterval(8 * 60 * 60);

        return ResponseEntity.ok(Map.of("message", "Code verified successfully."));
    }

    @GetMapping("/api/auth/session")
    public ResponseEntity<Map<String, String>> checkSession(
            HttpServletRequest request) throws SQLException {

        var session = request.getSession(false);
        if (session == null
                || !(session.getAttribute("contractorEmail") instanceof String email)) {
            return ResponseEntity.status(401)
                    .body(Map.of("message", "Please sign in."));
        }

        var contractor = findContractor(email);
        if (contractor == null) {
            session.invalidate();
            return ResponseEntity.status(401)
                    .body(Map.of("message", "Please sign in."));
        }

        return ResponseEntity.ok(Map.of(
                "name", (String) contractor.get("name"),
                "email", (String) contractor.get("email")));
    }

    @PostMapping("/api/auth/logout")
    public Map<String, String> signOut(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        return Map.of("message", "You’ve been signed out.");
    }

    private Map<String, Object> findContractor(String email) throws SQLException {
        return database.read(connection -> Database.one(connection,
                "SELECT email, full_name AS name FROM contractors WHERE email = ?", email));
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private ResponseEntity<Map<String, String>> invalidCode() {
        return ResponseEntity.badRequest().body(
                Map.of("message", "Code invalid, expired, locked, or already used."));
    }

    private byte[] loadSigningKey() {
        String configuredKey = System.getenv("PORTAL_OTP_SIGNING_KEY");

        if (configuredKey == null || configuredKey.isBlank()) {
            byte[] temporaryKey = new byte[32];
            random.nextBytes(temporaryKey);
            return temporaryKey;
        }

        byte[] key;
        try {
            key = Base64.getDecoder().decode(configuredKey.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "PORTAL_OTP_SIGNING_KEY must be Base64 encoded.", exception);
        }

        if (key.length < 32) {
            throw new IllegalStateException(
                    "PORTAL_OTP_SIGNING_KEY must decode to at least 32 bytes.");
        }

        return key;
    }

    private byte[] fingerprint(String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(code.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not process the sign-in code", exception);
        }
    }
}