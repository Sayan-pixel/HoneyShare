package com.honeyshare.identity.adapter.web;

import com.honeyshare.identity.service.InvitationService;
import com.honeyshare.security.AuthenticatedPrincipal;
import com.honeyshare.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin-only invitation management endpoints.
 *
 * <p>All endpoints require {@code ROLE_ADMIN} and MFA verification.
 * The raw invitation token is returned in the response body and must be
 * delivered to the recipient via a secure channel (email). It is never
 * stored server-side.
 */
@RestController
@RequestMapping("/api/v1/invitations")
@PreAuthorize("hasRole('ADMIN')")
public class InvitationController {

    private final InvitationService invitationService;

    public InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> createInvitation(
            @Valid @RequestBody CreateInvitationRequest body,
            HttpServletRequest request
    ) {
        // Verify MFA before any admin write action
        AuthenticatedPrincipal principal = SecurityContext.requireMfaVerified();

        String rawToken = invitationService.createInvitation(
                body.email(), principal.userId(), ipOf(request));

        // Return the raw token to the admin UI, which is responsible for
        // emailing it to the recipient. This token is a single-use credential.
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "invitationToken", rawToken,
                "message", "Send this token to the recipient via a secure channel."
        ));
    }

    @PostMapping("/{invitationId}/revoke")
    public ResponseEntity<Map<String, String>> revokeInvitation(
            @PathVariable String invitationId,
            HttpServletRequest request
    ) {
        AuthenticatedPrincipal principal = SecurityContext.requireMfaVerified();
        invitationService.revokeInvitation(invitationId, principal.userId(), ipOf(request));
        return ResponseEntity.ok(Map.of("message", "Invitation revoked."));
    }

    public record CreateInvitationRequest(
            @NotBlank @Email String email
    ) {}

    private String ipOf(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return (forwarded != null) ? forwarded.split(",")[0].strip() : request.getRemoteAddr();
    }
}

