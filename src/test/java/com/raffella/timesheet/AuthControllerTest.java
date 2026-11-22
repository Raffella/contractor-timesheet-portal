package com.raffella.timesheet;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class AuthControllerTest {

    @Test
    void rejectsAnOtpWithFewerThanSixDigits() throws Exception {
        var controller = new AuthController(mock(Database.class));
        var request = new MockHttpServletRequest();
        var code = new AuthController.VerifyRequest("alex@example.test", "123");

        var response = controller.verifyCode(code, request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNull(request.getSession(false));
    }
}