package com.honeyshare.identity.service;

import com.honeyshare.audit.AuditService;
import com.honeyshare.common.SecureTokens;
import com.honeyshare.common.exception.InvitationInvalidException;
import com.honeyshare.config.AuthProperties;
import com.honeyshare.identity.domain.*;
import com.honeyshare.identity.port.InvitationRepository;
import com.honeyshare.identity.port.UserRepository;
import com.honeyshare.security.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Handles invitation-only user registration.
 *
 * <p>Registration steps:
 * <ol>
 *   <li>Validate the invitation token (exists, not expired, not redeemed, not revoked,
 *       email matches if bound).</li>
 *   <li>Check the email is not already registered.</li>
 *   <li>Create the user in INVITED status.</li>
 *   <li>Mark the invitation redeemed atomically in the same transaction.</li>
 *   <li>Issue an email verification token (separate flow).</li>
 * </ol>
 *
 * <p>Email verification is handled by {@link EmailVerificationService}.
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final UserRepository userRepository;
    private final InvitationRepository invitationRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;
    private final AuditService auditService;
    private final RateLimiter rateLimiter;

    public RegistrationService(
            UserRepository userRepository,
            InvitationRepository invitationRepository,
            PasswordEncoder passwordEncoder,
            EmailVerificationService emailVerificationService,
            AuditService auditService,
            RateLimiter rateLimiter
    ) {
        this.userRepository = userRepository;
        this.invitationRepository = invitationRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailVerificationService = emailVerificationService;
        this.auditService = auditService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Register a new user using a valid invitation token.
     *
     * @param invitationToken  raw (unhashed) invitation token from the invitation link
     * @param email            user-supplied email address
     * @param rawPassword      user-supplied plaintext password (will be hashed; never logged)
     * @param ipAddress        for audit
     * @return the newly created {@link User}
     */
    @Transactional
    public User register(String invitationToken, String email, String rawPassword, String ipAddress) {
        String emailNormalized = email.strip().toLowerCase();

        rateLimiter.check("register", emailNormalized);

        // --- Validate invitation ---
        String tokenHash = SecureTokens.hash(invitationToken);
        Invitation invitation = invitationRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvitationInvalidException("not found"));

        if (!invitation.isUsable()) {
            throw new InvitationInvalidException("expired, revoked, or already redeemed");
        }
        if (!invitation.isEmailAllowed(emailNormalized)) {
            throw new InvitationInvalidException("email mismatch");
        }

        // --- Check uniqueness ---
        if (userRepository.existsByEmailNormalized(emailNormalized)) {
            // Do not distinguish "email already registered" from "invitation invalid"
            // to avoid account-existence enumeration.
            throw new InvitationInvalidException("cannot register with this invitation");
        }

        // --- Create user ---
        boolean isFirstUser = userRepository.count() == 0;
        UserRole assignedRole = isFirstUser ? UserRole.ADMIN : UserRole.USER;
        String passwordHash = passwordEncoder.encode(rawPassword);
        User user = User.create(emailNormalized, passwordHash, assignedRole);

        // Bootstrap administrator invitation auto-verifies email for seamless initial setup
        boolean isBootstrapInvite = invitation.getCreatedByUserId() == null;
        if (isBootstrapInvite) {
            user.verifyEmail(Instant.now());
        }
        userRepository.save(user);

        // --- Mark invitation redeemed in the same transaction ---
        invitation.markRedeemed(user.getId(), Instant.now());
        invitationRepository.save(invitation);

        log.info("User registered: userId={} role={} verified={}", user.getId(), assignedRole, user.isEmailVerified());
        auditService.record(user.getId(), "USER_REGISTERED", "user", user.getId(), ipAddress);

        // --- Issue verification email if not already verified ---
        if (!user.isEmailVerified()) {
            emailVerificationService.sendVerificationEmail(user, ipAddress);
        }

        return user;
    }
}

