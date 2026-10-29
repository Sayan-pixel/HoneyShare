package com.honeyshare.identity.service;

import com.honeyshare.audit.AuditService;
import com.honeyshare.common.SecureTokens;
import com.honeyshare.config.AuthProperties;
import com.honeyshare.identity.domain.EmailVerificationToken;
import com.honeyshare.identity.domain.User;
import com.honeyshare.identity.port.EmailVerificationTokenRepository;
import com.honeyshare.identity.port.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Manages email ownership verification for new users.
 *
 * <p>In local development (no email provider configured) the raw token is
 * emitted to the application log so developers can confirm accounts without
 * real email. This is clearly marked as DEV_ONLY and must never be enabled
 * in production.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final AuthProperties authProperties;
    private final AuditService auditService;

    public EmailVerificationService(
            EmailVerificationTokenRepository tokenRepository,
            UserRepository userRepository,
            AuthProperties authProperties,
            AuditService auditService
    ) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.authProperties = authProperties;
        this.auditService = auditService;
    }

    /**
     * Create a verification token and trigger sending the verification email.
     * The raw token is passed to the email system (not persisted).
     */
    @Transactional
    public void sendVerificationEmail(User user, String ipAddress) {
        String rawToken = SecureTokens.generateRawToken();
        String tokenHash = SecureTokens.hash(rawToken);
        Instant expiresAt = Instant.now()
                .plus(authProperties.getEmailVerificationExpiryHours(), ChronoUnit.HOURS);

        EmailVerificationToken token = EmailVerificationToken.create(user.getId(), tokenHash, expiresAt);
        tokenRepository.save(token);

        // TODO (Phase 4): send actual email via outbox/notification service.
        // For local dev: log the verification link so developers can confirm accounts.
        // SECURITY: Remove this log line before deploying to any non-local environment.
        log.warn("[DEV_ONLY] Email verification token for userId={}: rawToken={}", user.getId(), rawToken);
    }

    /**
     * Confirm a verification token, activate the user's account, and invalidate the token.
     *
     * @param rawToken  the raw token from the verification link
     * @param ipAddress for audit
     */
    @Transactional
    public void confirmVerification(String rawToken, String ipAddress) {
        String tokenHash = SecureTokens.hash(rawToken);
        EmailVerificationToken token = tokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new IllegalArgumentException("Verification token not found or already used."));

        if (!token.isUsable()) {
            throw new IllegalStateException("Verification token has expired or been used.");
        }

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new IllegalStateException("User not found for verification token."));

        Instant now = Instant.now();
        token.markUsed(now);
        tokenRepository.save(token);

        user.verifyEmail(now);
        userRepository.save(user);

        log.info("Email verified for userId={}", user.getId());
        auditService.record(user.getId(), "EMAIL_VERIFIED", "user", user.getId(), ipAddress);
    }
}

