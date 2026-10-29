package com.honeyshare.retention;

import com.honeyshare.audit.AuditService;
import com.honeyshare.config.TransferProperties;
import com.honeyshare.downloads.port.DownloadSessionRepository;
import com.honeyshare.quotas.QuotaService;
import com.honeyshare.storage.StoragePort;
import com.honeyshare.transfers.domain.PrivacyMode;
import com.honeyshare.transfers.domain.Transfer;
import com.honeyshare.transfers.domain.TransferState;
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
class DeletionServiceTest {

    @Mock TransferRepository transferRepository;
    @Mock DownloadSessionRepository downloadSessionRepository;
    @Mock StoragePort storagePort;
    @Mock QuotaService quotaService;
    @Mock AuditService auditService;

    TransferProperties transferProperties;
    DeletionService deletionService;

    @BeforeEach
    void setUp() {
        transferProperties = new TransferProperties();
        deletionService = new DeletionService(
                transferRepository, downloadSessionRepository, storagePort, quotaService, transferProperties, auditService);
    }

    @Test
    void deleteTransferContent_storageConfirmed_marksDeletedAndReleasesQuota() {
        Instant now = Instant.now();
        Transfer t = Transfer.create("sender-1", "file.pdf", 1000, null, PrivacyMode.STANDARD, "hash");
        t.markUploading();
        t.markVerifying();
        t.markStoredAndVerified("hash", now.minus(48, ChronoUnit.HOURS), now.minus(24, ChronoUnit.HOURS));
        t.markExpired(now.minus(24, ChronoUnit.HOURS));

        when(storagePort.deleteObject(t.getStorageObjectKey())).thenReturn(true);

        boolean result = deletionService.deleteTransferContent(t, now);

        assertThat(result).isTrue();
        assertThat(t.getState()).isEqualTo(TransferState.DELETED);
        assertThat(t.getDeletedAt()).isEqualTo(now);

        verify(quotaService).releaseCommittedReservation(t.getId());
        verify(transferRepository, atLeastOnce()).save(t);
        verify(auditService).record(eq(null), eq("TRANSFER_DELETED"), eq("transfer"), eq(t.getId()), eq("SYSTEM"));
    }

    @Test
    void deleteTransferContent_storageFails_retainsDeletionPendingStateWithoutReleasingQuota() {
        Instant now = Instant.now();
        Transfer t = Transfer.create("sender-1", "file.pdf", 1000, null, PrivacyMode.STANDARD, "hash");
        t.markUploading();
        t.markVerifying();
        t.markStoredAndVerified("hash", now.minus(48, ChronoUnit.HOURS), now.minus(24, ChronoUnit.HOURS));
        t.markExpired(now.minus(24, ChronoUnit.HOURS));

        when(storagePort.deleteObject(t.getStorageObjectKey())).thenReturn(false);

        boolean result = deletionService.deleteTransferContent(t, now);

        assertThat(result).isFalse();
        assertThat(t.getState()).isEqualTo(TransferState.DELETION_PENDING);
        assertThat(t.getDeletedAt()).isNull();

        verify(quotaService, never()).releaseCommittedReservation(any());
        verify(transferRepository).save(t);
    }

    @Test
    void processEligibleDeletions_activeDownloadInGracePeriod_defersDeletion() {
        Instant now = Instant.now();
        Transfer t = Transfer.create("sender-1", "file.pdf", 1000, null, PrivacyMode.STANDARD, "hash");
        t.markUploading();
        t.markVerifying();
        t.markStoredAndVerified("hash", now.minus(48, ChronoUnit.HOURS), now.minus(25, ChronoUnit.HOURS));
        t.markExpired(now.minus(25, ChronoUnit.HOURS));

        when(transferRepository.findTransfersEligibleForDeletion(any())).thenReturn(List.of(t));
        when(transferRepository.findByState(TransferState.DELETION_PENDING)).thenReturn(List.of());
        when(downloadSessionRepository.hasActiveUnexpiredSessions(t.getId(), now)).thenReturn(true);

        List<String> deleted = deletionService.processEligibleDeletions(now);

        assertThat(deleted).isEmpty();
        verify(storagePort, never()).deleteObject(any());
        assertThat(t.getState()).isEqualTo(TransferState.EXPIRED);
    }
}

