package com.honeyshare.downloads.service;

import com.honeyshare.audit.AuditService;
import com.honeyshare.common.exception.EmailNotVerifiedException;
import com.honeyshare.common.exception.RecipientNotAuthorizedException;
import com.honeyshare.common.exception.TransferExpiredException;
import com.honeyshare.config.TransferProperties;
import com.honeyshare.downloads.domain.DownloadSession;
import com.honeyshare.downloads.domain.DownloadSessionState;
import com.honeyshare.downloads.port.DownloadSessionRepository;
import com.honeyshare.identity.domain.User;
import com.honeyshare.identity.domain.UserRole;
import com.honeyshare.identity.port.UserRepository;
import com.honeyshare.storage.StoragePort;
import com.honeyshare.storage.StorageProperties;
import com.honeyshare.transfers.domain.*;
import com.honeyshare.transfers.port.TransferRecipientRepository;
import com.honeyshare.transfers.port.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DownloadServiceTest {

    @Mock TransferRepository transferRepository;
    @Mock TransferRecipientRepository recipientRepository;
    @Mock UserRepository userRepository;
    @Mock DownloadSessionRepository downloadSessionRepository;
    @Mock StoragePort storagePort;
    @Mock AuditService auditService;
    @Mock com.honeyshare.notifications.service.OutboxService outboxService;

    StorageProperties storageProperties;
    TransferProperties transferProperties;
    DownloadService downloadService;

    @BeforeEach
    void setUp() {
        storageProperties = new StorageProperties();
        transferProperties = new TransferProperties();
        downloadService = new DownloadService(
                transferRepository,
                recipientRepository,
                userRepository,
                downloadSessionRepository,
                storagePort,
                storageProperties,
                transferProperties,
                auditService,
                outboxService
        );
    }

    @Test
    void authorizeDownload_verifiedRecipient_generatesPresignedUrlAndCreatesSession() {
        User recipient = User.create("recipient@example.com", "hash", UserRole.USER);
        recipient.verifyEmail(Instant.now());

        Transfer t = Transfer.create("sender-1", "report.pdf", 5000, "application/pdf", PrivacyMode.STANDARD, "hash123");
        t.markUploading();
        t.markVerifying();
        t.markStoredAndVerified("hash123", Instant.now(), Instant.now().plusSeconds(86400));
        t.makeAvailableToRecipient();

        TransferRecipient tr = TransferRecipient.create(t.getId(), recipient.getId(), true);

        when(userRepository.findById(recipient.getId())).thenReturn(Optional.of(recipient));
        when(transferRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(recipientRepository.findByTransferIdAndRecipientUserId(t.getId(), recipient.getId()))
                .thenReturn(Optional.of(tr));
        when(downloadSessionRepository.findFirstByTransferIdAndRecipientUserIdAndStateInOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(storagePort.generatePresignedDownloadUrl(eq(t.getStorageObjectKey()), any(Duration.class), eq("report.pdf")))
                .thenReturn("https://storage.local/download/report.pdf");

        var auth = downloadService.authorizeDownload(t.getId(), recipient.getId(), "127.0.0.1");

        assertThat(auth.presignedDownloadUrl()).isEqualTo("https://storage.local/download/report.pdf");
        assertThat(auth.originalFilename()).isEqualTo("report.pdf");
        assertThat(tr.getState()).isEqualTo(RecipientState.DOWNLOAD_STARTED);
        verify(downloadSessionRepository).save(any(DownloadSession.class));
    }

    @Test
    void authorizeDownload_unverifiedRecipient_throwsEmailNotVerifiedException() {
        User recipient = User.create("recipient@example.com", "hash", UserRole.USER);
        // recipient email is not verified

        when(userRepository.findById(recipient.getId())).thenReturn(Optional.of(recipient));

        assertThatThrownBy(() -> downloadService.authorizeDownload("transfer-1", recipient.getId(), "127.0.0.1"))
                .isInstanceOf(EmailNotVerifiedException.class);
    }

    @Test
    void authorizeDownload_revokedRecipient_throwsRecipientNotAuthorizedException() {
        User recipient = User.create("recipient@example.com", "hash", UserRole.USER);
        recipient.verifyEmail(Instant.now());

        Transfer t = Transfer.create("sender-1", "report.pdf", 5000, null, PrivacyMode.STANDARD, "hash123");
        t.markUploading();
        t.markVerifying();
        t.markStoredAndVerified("hash123", Instant.now(), Instant.now().plusSeconds(86400));
        t.makeAvailableToRecipient();

        TransferRecipient tr = TransferRecipient.create(t.getId(), recipient.getId(), true);
        tr.markCancelled();

        when(userRepository.findById(recipient.getId())).thenReturn(Optional.of(recipient));
        when(transferRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(recipientRepository.findByTransferIdAndRecipientUserId(t.getId(), recipient.getId()))
                .thenReturn(Optional.of(tr));

        assertThatThrownBy(() -> downloadService.authorizeDownload(t.getId(), recipient.getId(), "127.0.0.1"))
                .isInstanceOf(RecipientNotAuthorizedException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    void authorizeDownload_expiredTransferWithNoActiveSession_throwsTransferExpiredException() {
        User recipient = User.create("recipient@example.com", "hash", UserRole.USER);
        recipient.verifyEmail(Instant.now());

        Transfer t = Transfer.create("sender-1", "report.pdf", 5000, null, PrivacyMode.STANDARD, "hash123");
        t.markUploading();
        t.markVerifying();
        t.markStoredAndVerified("hash123", Instant.now().minus(48, ChronoUnit.HOURS), Instant.now().minus(24, ChronoUnit.HOURS));
        t.markExpired(Instant.now());

        TransferRecipient tr = TransferRecipient.create(t.getId(), recipient.getId(), true);

        when(userRepository.findById(recipient.getId())).thenReturn(Optional.of(recipient));
        when(transferRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(recipientRepository.findByTransferIdAndRecipientUserId(t.getId(), recipient.getId()))
                .thenReturn(Optional.of(tr));
        when(downloadSessionRepository.findFirstByTransferIdAndRecipientUserIdAndStateInOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> downloadService.authorizeDownload(t.getId(), recipient.getId(), "127.0.0.1"))
                .isInstanceOf(TransferExpiredException.class);
    }

    @Test
    void authorizeDownload_expiredTransferWithActiveSessionInGracePeriod_allowsResume() {
        User recipient = User.create("recipient@example.com", "hash", UserRole.USER);
        recipient.verifyEmail(Instant.now());

        // Transfer expired 1 hour ago, but grace period deadline is 23 hours in the future
        Transfer t = Transfer.create("sender-1", "report.pdf", 5000, "application/pdf", PrivacyMode.STANDARD, "hash123");
        t.markUploading();
        t.markVerifying();
        Instant transferExpiry = Instant.now().minus(1, ChronoUnit.HOURS);
        t.markStoredAndVerified("hash123", Instant.now().minus(25, ChronoUnit.HOURS), transferExpiry);
        t.markExpired(Instant.now());

        TransferRecipient tr = TransferRecipient.create(t.getId(), recipient.getId(), true);

        // Pre-existing session created before transfer expiry, valid for 24h grace
        Instant graceExpiry = transferExpiry.plus(24, ChronoUnit.HOURS);
        DownloadSession existingSession = DownloadSession.create(t.getId(), recipient.getId(), graceExpiry, "127.0.0.1");

        when(userRepository.findById(recipient.getId())).thenReturn(Optional.of(recipient));
        when(transferRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(recipientRepository.findByTransferIdAndRecipientUserId(t.getId(), recipient.getId()))
                .thenReturn(Optional.of(tr));
        when(downloadSessionRepository.findFirstByTransferIdAndRecipientUserIdAndStateInOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.of(existingSession));
        when(storagePort.generatePresignedDownloadUrl(eq(t.getStorageObjectKey()), any(Duration.class), eq("report.pdf")))
                .thenReturn("https://storage.local/download/report.pdf");

        var auth = downloadService.authorizeDownload(t.getId(), recipient.getId(), "127.0.0.1");

        assertThat(auth.presignedDownloadUrl()).isNotNull();
        assertThat(existingSession.getReconnectCount()).isEqualTo(1);
        assertThat(existingSession.getExpiresAt()).isEqualTo(graceExpiry); // Grace clock NOT reset
    }

    @Test
    void completeDownloadSession_marksCompletedReportedAndAcknowledges() {
        Instant grace = Instant.now().plus(24, ChronoUnit.HOURS);
        DownloadSession session = DownloadSession.create("transfer-1", "recipient-1", grace, "127.0.0.1");
        TransferRecipient tr = TransferRecipient.create("transfer-1", "recipient-1", true);

        when(downloadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(recipientRepository.findByTransferIdAndRecipientUserId("transfer-1", "recipient-1"))
                .thenReturn(Optional.of(tr));
        when(recipientRepository.findByTransferId("transfer-1")).thenReturn(List.of(tr));

        downloadService.completeDownloadSession(session.getId(), "recipient-1", "hash123", "127.0.0.1");

        assertThat(session.getState()).isEqualTo(DownloadSessionState.COMPLETED_REPORTED);
        assertThat(tr.getState()).isEqualTo(RecipientState.VERIFIED_AT_RECEIVER);
        verify(downloadSessionRepository).save(session);
    }

    @Test
    void openDownloadRangeStream_returnsPartialPayloadWithAccurateRange() {
        Transfer t = Transfer.create("sender-1", "report.pdf", 5000, "application/pdf", PrivacyMode.STANDARD, "hash123");
        t.markUploading();
        t.markVerifying();
        t.markStoredAndVerified("hash123", Instant.now(), Instant.now().plusSeconds(86400));
        t.makeAvailableToRecipient();

        DownloadSession session = DownloadSession.create(t.getId(), "recipient-1", Instant.now().plus(24, ChronoUnit.HOURS), "127.0.0.1");

        when(downloadSessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(transferRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(storagePort.openObjectRangeStream(eq(t.getStorageObjectKey()), eq(100L), eq(500L)))
                .thenReturn(new java.io.ByteArrayInputStream(new byte[401]));

        var payload = downloadService.openDownloadRangeStream(session.getId(), "recipient-1", 100L, 500L);

        assertThat(payload).isNotNull();
        assertThat(payload.isPartial()).isTrue();
        assertThat(payload.rangeStart()).isEqualTo(100L);
        assertThat(payload.rangeEnd()).isEqualTo(500L);
        assertThat(payload.sizeBytes()).isEqualTo(401L);
    }
}
