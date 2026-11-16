package com.raffella.timesheet;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;

@RestController
public class AuthController {

    private static final String DEMO_EMAIL = "alex@example.test";

    private final SecureRandom random = new SecureRandom();
    private final byte[] signingKey = random.generateSeed(32);

    private byte[] codeHash;
    private Instant expiresAt;
    private int failedAttempts;

    public record CodeRequest(String email) {}
    public record VerifyRequest(String email, String code) {}

    @PostMapping("/api/auth/request")
    public synchronized Map<String, String> requestCode(@RequestBody CodeRequest request) {
        if (isDemoEmail(request.email())) {
            String code = String.valueOf(100_000 + random.nextInt(900_000));

            codeHash = fingerprint(code);
            expiresAt = Instant.now().plusSeconds(600);
            failedAttempts = 0;

            // Local practice only. Read the code in IntelliJ's Run console.
            System.out.println("LOCAL OTP for " + DEMO_EMAIL + ": " + code);
        }

        return Map.of("message", "If this email is registered, a code was sent.");
    }

    @PostMapping("/api/auth/verify")
    public synchronized ResponseEntity<Map<String, String>> verifyCode(
            @RequestBody VerifyRequest request,
            HttpServletRequest servletRequest) {

        String code = request.code();

        if (!isDemoEmail(request.email())
                || code == null
                || !code.matches("[0-9]{6}")
                || codeHash == null
                || !Instant.now().isBefore(expiresAt)
                || failedAttempts >= 5) {
            return invalidCode();
        }

        if (!MessageDigest.isEqual(codeHash, fingerprint(code))) {
            failedAttempts++;
            return invalidCode();
        }

        codeHash = null;

        HttpSession previousSession = servletRequest.getSession(false);
        if (previousSession != null) {
            previousSession.invalidate();
        }

        HttpSession session = servletRequest.getSession();
        session.setAttribute("contractorEmail", DEMO_EMAIL);
        session.setMaxInactiveInterval(8 * 60 * 60);

        return ResponseEntity.ok(Map.of("message", "Code verified successfully."));
    }

    private boolean isDemoEmail(String email) {
        return email != null && DEMO_EMAIL.equalsIgnoreCase(email.trim());
    }

    private ResponseEntity<Map<String, String>> invalidCode() {
        return ResponseEntity.badRequest().body(
                Map.of("message", "Code invalid, expired, locked, or already used."));
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

    @GetMapping("/api/auth/session")
    public ResponseEntity<Map<String, String>> checkSession(
            HttpServletRequest request) {

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("contractorEmail") == null) {
            return ResponseEntity.status(401)
                    .body(Map.of("message", "Please sign in."));
        }

        String email = (String) session.getAttribute("contractorEmail");
        return ResponseEntity.ok(Map.of("name", "Alex Morgan", "email", email));
    }

    @PostMapping("/api/auth/logout")
    public Map<String, String> signOut(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        return Map.of("message", "You’ve been signed out.");
    }
}