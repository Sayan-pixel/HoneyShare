package com.honeyshare.identity.service;

import com.honeyshare.audit.AuditService;
import com.honeyshare.config.AuthProperties;
import com.honeyshare.identity.domain.Invitation;
import com.honeyshare.identity.port.InvitationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvitationServiceTest {

    @Mock InvitationRepository invitationRepository;
    @Mock AuditService auditService;

    AuthProperties authProperties;
    InvitationService service;

    @BeforeEach
    void setUp() {
        authProperties = new AuthProperties();
        service = new InvitationService(invitationRepository, authProperties, auditService);
    }

    @Test
    void createInvitation_generatesSingleUseToken() {
        when(invitationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        String rawToken = service.createInvitation("bob@example.com", "admin-01", "127.0.0.1");

        assertThat(rawToken).isNotBlank();
        verify(invitationRepository).save(argThat(inv ->
                inv.getEmailNormalized().equals("bob@example.com")
                        && inv.getCreatedByUserId().equals("admin-01")
                        && inv.isUsable()));
    }

    @Test
    void revokeInvitation_setsRevokedAt() {
        Invitation inv = Invitation.create("bob@example.com", "hash", "admin-01", Instant.now().plusSeconds(3600));
        when(invitationRepository.findById(inv.getId())).thenReturn(Optional.of(inv));

        service.revokeInvitation(inv.getId(), "admin-01", "127.0.0.1");

        assertThat(inv.isUsable()).isFalse();
        verify(invitationRepository).save(inv);
    }

    @Test
    void revokeInvitation_alreadyRedeemed_throws() {
        Invitation inv = Invitation.create("bob@example.com", "hash", "admin-01", Instant.now().plusSeconds(3600));
        inv.markRedeemed("user-01", Instant.now());
        when(invitationRepository.findById(inv.getId())).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> service.revokeInvitation(inv.getId(), "admin-01", "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot revoke an already-redeemed invitation");
    }
}

