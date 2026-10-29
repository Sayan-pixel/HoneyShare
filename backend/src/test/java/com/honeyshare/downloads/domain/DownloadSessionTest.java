package com.honeyshare.downloads.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DownloadSessionTest {

    @Test
    void create_initializesSessionCorrectly() {
        Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);
        DownloadSession session = DownloadSession.create("transfer-1", "recipient-1", expiresAt, "192.168.1.1");

        assertThat(session.getId()).isNotBlank();
        assertThat(session.getTransferId()).isEqualTo("transfer-1");
        assertThat(session.getRecipientUserId()).isEqualTo("recipient-1");
        assertThat(session.getState()).isEqualTo(DownloadSessionState.AUTHORIZED);
        assertThat(session.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(session.getReconnectCount()).isZero();
        assertThat(session.getIpAddress()).isEqualTo("192.168.1.1");
    }

    @Test
    void recordReconnect_incrementsCounterAndPreservesGraceExpiry() {
        Instant originalExpiresAt = Instant.now().plus(12, ChronoUnit.HOURS);
        DownloadSession session = DownloadSession.create("transfer-1", "recipient-1", originalExpiresAt, "127.0.0.1");

        session.recordReconnect();
        assertThat(session.getReconnectCount()).isEqualTo(1);
        assertThat(session.getState()).isEqualTo(DownloadSessionState.IN_PROGRESS);
        assertThat(session.getExpiresAt()).isEqualTo(originalExpiresAt); // Never reset

        session.recordReconnect();
        assertThat(session.getReconnectCount()).isEqualTo(2);
        assertThat(session.getExpiresAt()).isEqualTo(originalExpiresAt); // Never reset
    }

    @Test
    void recordReconnect_terminalSession_throwsIllegalStateException() {
        Instant expiresAt = Instant.now().plus(24, ChronoUnit.HOURS);
        DownloadSession session = DownloadSession.create("transfer-1", "recipient-1", expiresAt, "127.0.0.1");
        session.markCompleted(Instant.now());

        assertThatThrownBy(session::recordReconnect)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot reconnect to terminal download session");
    }

    @Test
    void isExpired_evaluatesGraceExpiryCorrectly() {
        Instant past = Instant.now().minus(5, ChronoUnit.MINUTES);
        DownloadSession session = DownloadSession.create("transfer-1", "recipient-1", past, "127.0.0.1");

        assertThat(session.isExpired(Instant.now())).isTrue();

        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
        DownloadSession futureSession = DownloadSession.create("transfer-1", "recipient-1", future, "127.0.0.1");

        assertThat(futureSession.isExpired(Instant.now())).isFalse();
    }
}

