package com.honeyshare.identity.adapter.web;

import com.honeyshare.identity.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Authentication REST endpoints.
 *
 * <p>All mutating endpoints accept JSON. The Authorization header carries
 * {@code Bearer <rawToken>} for authenticated requests.
 *
 * <p>Error responses follow the structured {@code {code, message}} contract
 * defined in {@link GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final RegistrationService registrationService;
    private final AuthenticationService authService;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;
    private final MfaService mfaService;

    public AuthController(
            RegistrationService registrationService,
            AuthenticationService authService,
            EmailVerificationService emailVerificationService,
            PasswordResetService passwordResetService,
            MfaService mfaService
    ) {
        this.registrationService = registrationService;
        this.authService = authService;
        this.emailVerificationService = emailVerificationService;
        this.passwordResetService = passwordResetService;
        this.mfaService = mfaService;
    }

    // ----------------------------------------------------------------
    // Registration
    // ----------------------------------------------------------------

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(
            @Valid @RequestBody RegisterRequest body,
            HttpServletRequest request
    ) {
        var user = registrationService.register(
                body.invitationToken(), body.email(), body.password(), ipOf(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "userId", user.getId(),
                "message", "Registration successful. Check your email to verify your address."
        ));
    }

    // ----------------------------------------------------------------
    // Login / Logout
    // ----------------------------------------------------------------

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @Valid @RequestBody LoginRequest body,
            HttpServletRequest request
    ) {
        var result = authService.login(
                body.email(), body.password(), ipOf(request), request.getHeader("User-Agent"));
        return ResponseEntity.ok(Map.of(
                "sessionToken", result.rawSessionToken(),
                "sessionId", result.sessionId(),
                "mfaRequired", result.mfaRequired()
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        String token = extractBearerToken(request);
        if (token != null) {
            authService.logout(token, ipOf(request));
        }
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------------
    // Email verification
    // ----------------------------------------------------------------

    @PostMapping("/email-verification/request")
    public ResponseEntity<Map<String, String>> requestEmailVerification(
            @Valid @RequestBody EmailRequest body,
            HttpServletRequest request
    ) {
        // Re-send verification email (handled by registration flow; this is a resend)
        // TODO: implement resend flow properly in Phase 4 via notification outbox
        return ResponseEntity.accepted().body(Map.of(
                "message", "If this email is registered and unverified, a new verification link has been sent."));
    }

    @PostMapping("/email-verification/confirm")
    public ResponseEntity<Map<String, String>> confirmEmailVerification(
            @Valid @RequestBody TokenRequest body,
            HttpServletRequest request
    ) {
        emailVerificationService.confirmVerification(body.token(), ipOf(request));
        return ResponseEntity.ok(Map.of("message", "Email verified. You may now log in."));
    }

    // ----------------------------------------------------------------
    // Password reset
    // ----------------------------------------------------------------

    @PostMapping("/password-reset/request")
    public ResponseEntity<Map<String, String>> requestPasswordReset(
            @Valid @RequestBody EmailRequest body,
            HttpServletRequest request
    ) {
        passwordResetService.requestReset(body.email(), ipOf(request));
        // Always return same message to prevent email enumeration
        return ResponseEntity.accepted().body(Map.of(
                "message", "If this email is registered, a password reset link has been sent."));
    }

    @PostMapping("/password-reset/confirm")
    public ResponseEntity<Map<String, String>> confirmPasswordReset(
            @Valid @RequestBody PasswordResetConfirmRequest body,
            HttpServletRequest request
    ) {
        passwordResetService.confirmReset(body.token(), body.newPassword(), ipOf(request));
        return ResponseEntity.ok(Map.of("message", "Password updated. Please log in again."));
    }

    // ----------------------------------------------------------------
    // MFA
    // ----------------------------------------------------------------

    @PostMapping("/mfa/enroll")
    public ResponseEntity<Map<String, Object>> enrollMfa(
            @Valid @RequestBody MfaEnrollRequest body,
            HttpServletRequest request
    ) {
        var data = mfaService.beginTotpEnrollment(body.userId(), body.email());
        return ResponseEntity.ok(Map.of(
                "secret", data.secret(),
                "provisioningUri", data.provisioningUri(),
                "qrCodeDataUrl", data.qrCodeDataUrl() != null ? data.qrCodeDataUrl() : ""
        ));
    }

    @PostMapping("/mfa/confirm")
    public ResponseEntity<Map<String, Object>> confirmMfaEnrollment(
            @Valid @RequestBody MfaConfirmRequest body,
            HttpServletRequest request
    ) {
        List<String> codes = mfaService.confirmTotpEnrollment(
                body.userId(), body.secret(), body.code(), ipOf(request));
        return ResponseEntity.ok(Map.of(
                "recoveryCodes", codes,
                "message", "MFA enrolled. Store these recovery codes safely — they will not be shown again."
        ));
    }

    @PostMapping("/mfa/verify")
    public ResponseEntity<Map<String, String>> verifyMfa(
            @Valid @RequestBody MfaVerifyRequest body,
            HttpServletRequest request
    ) {
        authService.verifyMfa(body.sessionToken(), body.code(), ipOf(request));
        return ResponseEntity.ok(Map.of("message", "MFA verified."));
    }

    @PostMapping("/mfa/recovery")
    public ResponseEntity<Map<String, String>> recoveryCode(
            @Valid @RequestBody MfaRecoveryRequest body,
            HttpServletRequest request
    ) {
        mfaService.useRecoveryCode(body.userId(), body.recoveryCode(), ipOf(request));
        return ResponseEntity.ok(Map.of("message", "Recovery code accepted."));
    }

    // ----------------------------------------------------------------
    // Request / Response DTOs
    // ----------------------------------------------------------------

    public record RegisterRequest(
            @NotBlank String invitationToken,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 12, max = 128,
                    message = "Password must be 12-128 characters") String password
    ) {}

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {}

    public record EmailRequest(
            @NotBlank @Email String email
    ) {}

    public record TokenRequest(
            @NotBlank String token
    ) {}

    public record PasswordResetConfirmRequest(
            @NotBlank String token,
            @NotBlank @Size(min = 12, max = 128) String newPassword
    ) {}

    public record MfaEnrollRequest(
            @NotBlank String userId,
            @NotBlank @Email String email
    ) {}

    public record MfaConfirmRequest(
            @NotBlank String userId,
            @NotBlank String secret,
            @NotBlank @Size(min = 6, max = 6) String code
    ) {}

    public record MfaVerifyRequest(
            @NotBlank String sessionToken,
            @NotBlank @Size(min = 6, max = 6) String code
    ) {}

    public record MfaRecoveryRequest(
            @NotBlank String userId,
            @NotBlank String recoveryCode
    ) {}

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private String ipOf(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return (forwarded != null) ? forwarded.split(",")[0].strip() : request.getRemoteAddr();
    }

    private String extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).strip();
        }
        return null;
    }
}

