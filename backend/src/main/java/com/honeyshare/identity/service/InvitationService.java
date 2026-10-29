package com.honeyshare.identity.service;

import com.honeyshare.audit.AuditService;
import com.honeyshare.common.SecureTokens;
import com.honeyshare.common.exception.AuthenticationException;
import com.honeyshare.common.exception.InvitationInvalidException;
import com.honeyshare.config.AuthProperties;
import com.honeyshare.identity.domain.Invitation;
import com.honeyshare.identity.port.InvitationRepository;
import com.honeyshare.security.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Admin-only service for creating and revoking invitations.
 */
@Service
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    private final InvitationRepository invitationRepository;
    private final AuthProperties authProperties;
    private final AuditService auditService;

    public InvitationService(
            InvitationRepository invitationRepository,
            AuthProperties authProperties,
            AuditService auditService
    ) {
        this.invitationRepository = invitationRepository;
        this.authProperties = authProperties;
        this.auditService = auditService;
    }

    /**
     * Create a new email-bound, single-use invitation.
     *
     * @return the raw invitation token to be emailed to the recipient (not stored)
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public String createInvitation(String targetEmail, String createdByUserId, String ipAddress) {
        // Admin MFA is enforced by SecurityContext.requireMfaVerified() in the controller
        String emailNormalized = targetEmail.strip().toLowerCase();

        String rawToken = SecureTokens.generateRawToken();
        String tokenHash = SecureTokens.hash(rawToken);
        Instant expiresAt = Instant.now()
                .plus(authProperties.getInvitationExpiryHours(), ChronoUnit.HOURS);

        Invitation inv = Invitation.create(emailNormalized, tokenHash, createdByUserId, expiresAt);
        invitationRepository.save(inv);

        log.info("Invitation created: id={} by admin={} forEmail=[REDACTED]",
                inv.getId(), createdByUserId);
        auditService.record(createdByUserId, "INVITATION_CREATED", "invitation", inv.getId(), ipAddress);

        // The raw token is returned to the caller (controller), which will embed it
        // in an email link. It is never stored or logged beyond this return.
        return rawToken;
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void revokeInvitation(String invitationId, String adminUserId, String ipAddress) {
        Invitation inv = invitationRepository.findById(invitationId)
                .orElseThrow(() -> new IllegalArgumentException("Invitation not found."));

        if (inv.getRedeemedAt() != null) {
            throw new IllegalStateException("Cannot revoke an already-redeemed invitation.");
        }

        inv.revoke(Instant.now());
        invitationRepository.save(inv);

        log.info("Invitation revoked: id={} by admin={}", invitationId, adminUserId);
        auditService.record(adminUserId, "INVITATION_REVOKED", "invitation", invitationId, ipAddress);
    }
}

