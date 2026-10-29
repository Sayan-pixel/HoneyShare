package com.honeyshare.transfers.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferTest {

    @Test
    void create_sanitizesFilenamesAgainstPathTraversal() {
        Transfer t1 = Transfer.create(
                "user-1", "../../etc/passwd", 1024, "text/plain", PrivacyMode.STANDARD, "abc123hash");
        assertThat(t1.getOriginalFilename()).isEqualTo("passwd");

        Transfer t2 = Transfer.create(
                "user-1", "C:\\Windows\\System32\\cmd.exe", 1024, null, PrivacyMode.STANDARD, "abc123hash");
        assertThat(t2.getOriginalFilename()).isEqualTo("cmd.exe");

        Transfer t3 = Transfer.create(
                "user-1", "normal-report.pdf", 1024, "application/pdf", PrivacyMode.STANDARD, "abc123hash");
        assertThat(t3.getOriginalFilename()).isEqualTo("normal-report.pdf");
    }

    @Test
    void stateTransitions_followPrescribedSequence() {
        Transfer t = Transfer.create(
                "user-1", "file.dat", 1000, null, PrivacyMode.STANDARD, "expectedhash");
        assertThat(t.getState()).isEqualTo(TransferState.CREATED);

        t.markUploading();
        assertThat(t.getState()).isEqualTo(TransferState.UPLOADING);

        t.markVerifying();
        assertThat(t.getState()).isEqualTo(TransferState.VERIFYING);

        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(86400);
        t.markStoredAndVerified("expectedhash", now, expiry);
        assertThat(t.getState()).isEqualTo(TransferState.STORED_AND_VERIFIED);
        assertThat(t.getVerifiedSha256()).isEqualTo("expectedhash");

        t.makeAvailableToRecipient();
        assertThat(t.getState()).isEqualTo(TransferState.AVAILABLE_TO_RECIPIENT);
    }

    @Test
    void stateTransitions_illegalJumpThrows() {
        Transfer t = Transfer.create(
                "user-1", "file.dat", 1000, null, PrivacyMode.STANDARD, "expectedhash");

        // Cannot mark stored without being in VERIFYING state
        assertThatThrownBy(() -> t.markStoredAndVerified("hash", Instant.now(), Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancel_movesToCancelled() {
        Transfer t = Transfer.create(
                "user-1", "file.dat", 1000, null, PrivacyMode.STANDARD, "expectedhash");
        t.cancel();
        assertThat(t.getState()).isEqualTo(TransferState.CANCELLED);

        // Terminal state cannot be cancelled again
        assertThatThrownBy(t::cancel).isInstanceOf(IllegalStateException.class);
    }
}

