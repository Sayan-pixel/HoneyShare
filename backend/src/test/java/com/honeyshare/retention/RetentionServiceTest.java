package com.honeyshare.retention;

import com.honeyshare.audit.AuditService;
import com.honeyshare.downloads.domain.DownloadSession;
import com.honeyshare.downloads.domain.DownloadSessionState;
import com.honeyshare.downloads.port.DownloadSessionRepository;
import com.honeyshare.transfers.domain.*;
import com.honeyshare.transfers.port.TransferRecipientRepository;
import com.honeyshare.transfers.port.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RetentionServiceTest {

    @Mock TransferRepository transferRepository;
    @Mock TransferRecipientRepository recipientRepository;
    @Mock DownloadSessionRepository downloadSessionRepository;
    @Mock AuditService auditService;
    @Mock com.honeyshare.notifications.service.OutboxService outboxService;

    RetentionService retentionService;

    @BeforeEach
    void setUp() {
        retentionService = new RetentionService(
                transferRepository, recipientRepository, downloadSessionRepository, auditService, outboxService);
    }

    @Test
    void expireTransfers_pastDeadline_transitionsTransferAndRecipientsToExpired() {
        Instant now = Instant.now();
        Instant pastExpiry = now.minus(1, ChronoUnit.MINUTES);

        Transfer t = Transfer.create("sender-1", "file.pdf", 1000, null, PrivacyMode.STANDARD, "hash");
        t.markUploading();
        t.markVerifying();
        t.markStoredAndVerified("hash", now.minus(24, ChronoUnit.HOURS), pastExpiry);
        t.makeAvailableToRecipient();

        TransferRecipient tr = TransferRecipient.create(t.getId(), "recipient-1", true);

        when(transferRepository.findExpiredTransfers(now)).thenReturn(List.of(t));
        when(recipientRepository.findByTransferId(t.getId())).thenReturn(List.of(tr));

        int count = retentionService.expireTransfers(now);

        assertThat(count).isEqualTo(1);
        assertThat(t.getState()).isEqualTo(TransferState.EXPIRED);
        assertThat(tr.getState()).isEqualTo(RecipientState.EXPIRED);

        verify(transferRepository).save(t);
        verify(recipientRepository).save(tr);
        verify(auditService).record(eq(null), eq("TRANSFER_EXPIRED"), eq("transfer"), eq(t.getId()), eq("SYSTEM"));
    }

    @Test
    void expireActiveDownloadSessions_pastGraceDeadline_transitionsSessionToExpired() {
        Instant now = Instant.now();
        Instant pastGrace = now.minus(10, ChronoUnit.MINUTES);
        DownloadSession session = DownloadSession.create("transfer-1", "recipient-1", pastGrace, "127.0.0.1");

        when(downloadSessionRepository.findActiveExpiredSessions(now)).thenReturn(List.of(session));

        int count = retentionService.expireActiveDownloadSessions(now);

        assertThat(count).isEqualTo(1);
        assertThat(session.getState()).isEqualTo(DownloadSessionState.EXPIRED);
        verify(downloadSessionRepository).save(session);
    }
}

