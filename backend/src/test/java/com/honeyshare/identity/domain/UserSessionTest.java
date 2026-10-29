package com.honeyshare.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class UserSessionTest {

    @Test
    void freshSession_isValid() {
        UserSession s = UserSession.create(
                "user-01", "hash", Instant.now().plus(30, ChronoUnit.MINUTES),
                "127.0.0.1", "TestAgent");
        assertThat(s.isValid()).isTrue();
    }

    @Test
    void expiredSession_isNotValid() {
        UserSession s = UserSession.create(
                "user-01", "hash", Instant.now().minus(1, ChronoUnit.SECONDS),
                "127.0.0.1", "TestAgent");
        assertThat(s.isValid()).isFalse();
    }

    @Test
    void revokedSession_isNotValid() {
        UserSession s = UserSession.create(
                "user-01", "hash", Instant.now().plus(30, ChronoUnit.MINUTES),
                "127.0.0.1", "TestAgent");
        s.revoke(Instant.now());
        assertThat(s.isValid()).isFalse();
    }

    @Test
    void mfaVerification_isRecorded() {
        UserSession s = UserSession.create(
                "user-01", "hash", Instant.now().plus(30, ChronoUnit.MINUTES),
                "127.0.0.1", "TestAgent");
        assertThat(s.isMfaVerified()).isFalse();
        s.markMfaVerified(Instant.now());
        assertThat(s.isMfaVerified()).isTrue();
    }
}

