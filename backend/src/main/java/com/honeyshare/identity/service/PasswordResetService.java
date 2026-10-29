package com.honeyshare.identity.service;

import com.honeyshare.audit.AuditService;
import com.honeyshare.common.SecureTokens;
import com.honeyshare.common.exception.AuthenticationException;
import com.honeyshare.config.AuthProperties;
import com.honeyshare.identity.domain.PasswordResetToken;
import com.honeyshare.identity.domain.User;
import com.honeyshare.identity.port.PasswordResetTokenRepository;
import com.honeyshare.identity.port.SessionRepository;
import com.honeyshare.identity.port.UserRepository;
import com.honeyshare.security.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Handles password reset flow.
 *
 * <p>Per spec §3.2: password reset must never silently recover or decrypt E2E keys.
 * After a successful reset, all active sessions are revoked (forced re-login).
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final SessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties authProperties;
    private final AuditService auditService;
    private final RateLimiter rateLimiter;

    public PasswordResetService(
            UserRepository userRepository,
            PasswordResetTokenRepository resetTokenRepository,
            SessionRepository sessionRepository,
            PasswordEncoder passwordEncoder,
            AuthProperties authProperties,
            AuditService auditService,
            RateLimiter rateLimiter
    ) {
        this.userRepository = userRepository;
        this.resetTokenRepository = resetTokenRepository;
        this.sessionRepository = sessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.authProperties = authProperties;
        this.auditService = auditService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Request a password reset. Always returns the same response regardless of
     * whether the email is registered (prevents account enumeration).
     */
    @Transactional
    public void requestReset(String email, String ipAddress) {
        String emailNormalized = email.strip().toLowerCase();
        rateLimiter.check("password-reset", emailNormalized);

        userRepository.findByEmailNormalized(emailNormalized).ifPresent(user -> {
            String rawToken = SecureTokens.generateRawToken();
            String tokenHash = SecureTokens.hash(rawToken);
            Instant expiresAt = Instant.now()
                    .plus(authProperties.getPasswordResetExpiryMinutes(), ChronoUnit.MINUTES);

            PasswordResetToken token = PasswordResetToken.create(user.getId(), tokenHash, expiresAt);
            resetTokenRepository.save(token);

            // TODO (Phase 4): send email via notification outbox
            // For local dev: log to console so developers can test the flow
            log.warn("[DEV_ONLY] Password reset token for userId={}: rawToken={}", user.getId(), rawToken);
            auditService.record(user.getId(), "PASSWORD_RESET_REQUESTED", "user", user.getId(), ipAddress);
        });
    }

    /**
     * Confirm a password reset with the token and new password.
     * Revokes all existing sessions.
     */
    @Transactional
    public void confirmReset(String rawToken, String newRawPassword, String ipAddress) {
        String tokenHash = SecureTokens.hash(rawToken);
        PasswordResetToken token = resetTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new AuthenticationException("Invalid or expired password reset token."));

        if (!token.isUsable()) {
            throw new AuthenticationException("Password reset token has expired or been used.");
        }

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new IllegalStateException("User not found."));

        String newHash = passwordEncoder.encode(newRawPassword);
        Instant now = Instant.now();

        user.updatePasswordHash(newHash, now);
        userRepository.save(user);

        token.markUsed(now);
        resetTokenRepository.save(token);

        // Revoke all active sessions (force re-login on all devices)
        int revoked = sessionRepository.revokeAllForUser(user.getId(), now);

        log.info("Password reset confirmed for userId={}, {} sessions revoked", user.getId(), revoked);
        auditService.record(user.getId(), "PASSWORD_RESET_CONFIRMED", "user", user.getId(), ipAddress,
                "{\"sessionRevoked\":" + revoked + "}");
    }
}

