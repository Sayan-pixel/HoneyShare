package com.honeyshare.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InvitationTest {

    @Test
    void newInvitation_isUsable() {
        Invitation inv = buildInvitation("user@example.com", Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(inv.isUsable()).isTrue();
    }

    @Test
    void expiredInvitation_isNotUsable() {
        Invitation inv = buildInvitation("user@example.com", Instant.now().minus(1, ChronoUnit.SECONDS));
        assertThat(inv.isUsable()).isFalse();
    }

    @Test
    void revokedInvitation_isNotUsable() {
        Invitation inv = buildInvitation("user@example.com", Instant.now().plus(1, ChronoUnit.HOURS));
        inv.revoke(Instant.now());
        assertThat(inv.isUsable()).isFalse();
    }

    @Test
    void redeemedInvitation_isNotUsable() {
        Invitation inv = buildInvitation("user@example.com", Instant.now().plus(1, ChronoUnit.HOURS));
        inv.markRedeemed("user-01", Instant.now());
        assertThat(inv.isUsable()).isFalse();
    }

    @Test
    void emailBoundInvitation_rejectsWrongEmail() {
        Invitation inv = buildInvitation("target@example.com", Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(inv.isEmailAllowed("other@example.com")).isFalse();
        assertThat(inv.isEmailAllowed("TARGET@EXAMPLE.COM")).isTrue(); // case-insensitive
    }

    @Test
    void unboundInvitation_allowsAnyEmail() {
        Invitation inv = buildInvitation(null, Instant.now().plus(1, ChronoUnit.HOURS));
        assertThat(inv.isEmailAllowed("anyone@example.com")).isTrue();
    }

    private Invitation buildInvitation(String email, Instant expiresAt) {
        return Invitation.create(email, "token-hash-placeholder", "admin-01", expiresAt);
    }
}

