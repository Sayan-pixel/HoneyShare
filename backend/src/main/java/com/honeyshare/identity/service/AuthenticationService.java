package com.honeyshare.identity.service;

import com.honeyshare.audit.AuditService;
import com.honeyshare.common.SecureTokens;
import com.honeyshare.common.exception.AuthenticationException;
import com.honeyshare.common.exception.MfaRequiredException;
import com.honeyshare.config.AuthProperties;
import com.honeyshare.identity.domain.*;
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
 * Handles login, logout, and session management.
 *
 * <p>Session tokens are generated server-side. Only the SHA-256 hash is stored.
 * The raw token is returned to the client once at login and never stored or logged.
 *
 * <p>MFA enforcement: admins must verify MFA before the session is fully trusted.
 * Non-admin users may optionally have MFA; if enrolled, they must verify.
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final MfaService mfaService;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties authProperties;
    private final AuditService auditService;
    private final RateLimiter rateLimiter;

    public AuthenticationService(
            UserRepository userRepository,
            SessionRepository sessionRepository,
            MfaService mfaService,
            PasswordEncoder passwordEncoder,
            AuthProperties authProperties,
            AuditService auditService,
            RateLimiter rateLimiter
    ) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.mfaService = mfaService;
        this.passwordEncoder = passwordEncoder;
        this.authProperties = authProperties;
        this.auditService = auditService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Result of a successful login.
     *
     * @param rawSessionToken  the bearer token to be sent to the client (never log this)
     * @param sessionId        the session ID (safe to log)
     * @param mfaRequired      true when the caller must complete MFA before accessing resources
     */
    public record LoginResult(String rawSessionToken, String sessionId, boolean mfaRequired) {}

    /**
     * Authenticate with email and password.
     * Returns a session token and indicates whether MFA is required.
     */
    @Transactional
    public LoginResult login(String email, String rawPassword, String ipAddress, String userAgent) {
        String emailNormalized = email.strip().toLowerCase();
        rateLimiter.check("login", emailNormalized);

        // Use constant-time comparison path regardless of whether user exists
        // to avoid timing-based account enumeration.
        User user = userRepository.findByEmailNormalized(emailNormalized)
                .orElse(null);

        boolean passwordMatches = (user != null)
                && passwordEncoder.matches(rawPassword, user.getPasswordHash());

        if (!passwordMatches || user == null) {
            auditService.record(null, "LOGIN_FAILED", "user", emailNormalized, ipAddress);
            throw new AuthenticationException("Invalid email or password.");
        }

        if (!user.isActive() && user.getStatus() != UserStatus.INVITED) {
            auditService.record(user.getId(), "LOGIN_REJECTED_ACCOUNT_STATUS", "user", user.getId(), ipAddress,
                    "{\"status\":\"" + user.getStatus() + "\"}");
            throw new AuthenticationException("Account is not active.");
        }

        String rawToken = SecureTokens.generateRawToken();
        String tokenHash = SecureTokens.hash(rawToken);
        Instant expiresAt = Instant.now()
                .plus(authProperties.getSessionDurationMinutes(), ChronoUnit.MINUTES);

        UserSession session = UserSession.create(user.getId(), tokenHash, expiresAt, ipAddress, userAgent);

        boolean mfaRequired = mfaService.hasMfaEnabled(user.getId());
        if (!mfaRequired) {
            // No MFA enrolled — mark session fully verified immediately
            session.markMfaVerified(Instant.now());
        }

        sessionRepository.save(session);
        user.recordLogin(Instant.now());
        userRepository.save(user);

        log.info("Login: userId={} sessionId={} mfaRequired={}", user.getId(), session.getId(), mfaRequired);
        auditService.record(user.getId(), "LOGIN_SUCCESS", "user", user.getId(), ipAddress);

        return new LoginResult(rawToken, session.getId(), mfaRequired);
    }

    /**
     * Revoke the session identified by the raw bearer token.
     */
    @Transactional
    public void logout(String rawToken, String ipAddress) {
        String tokenHash = SecureTokens.hash(rawToken);
        sessionRepository.findBySessionTokenHash(tokenHash).ifPresent(session -> {
            session.revoke(Instant.now());
            sessionRepository.save(session);
            auditService.record(session.getUserId(), "LOGOUT", "session", session.getId(), ipAddress);
            log.info("Logout: userId={} sessionId={}", session.getUserId(), session.getId());
        });
    }

    /**
     * Complete MFA verification for the current session.
     */
    @Transactional
    public void verifyMfa(String rawToken, String totpCode, String ipAddress) {
        String tokenHash = SecureTokens.hash(rawToken);
        UserSession session = sessionRepository.findBySessionTokenHash(tokenHash)
                .orElseThrow(() -> new AuthenticationException("Session not found."));

        if (!session.isValid()) {
            throw new AuthenticationException("Session is invalid or expired.");
        }

        mfaService.verifyTotp(session.getUserId(), totpCode, ipAddress);
        session.markMfaVerified(Instant.now());
        sessionRepository.save(session);

        log.info("MFA verified: userId={} sessionId={}", session.getUserId(), session.getId());
        auditService.record(session.getUserId(), "MFA_VERIFIED", "session", session.getId(), ipAddress);
    }
}

