package com.honeyshare.transfers.service;

import com.honeyshare.audit.AuditService;
import com.honeyshare.quotas.QuotaService;
import com.honeyshare.storage.StoragePort;
import com.honeyshare.transfers.domain.*;
import com.honeyshare.transfers.port.FileChunkRepository;
import com.honeyshare.transfers.port.TransferRepository;
import com.honeyshare.transfers.port.UploadSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock UploadSessionRepository sessionRepository;
    @Mock FileChunkRepository chunkRepository;
    @Mock TransferRepository transferRepository;
    @Mock StoragePort storagePort;
    @Mock QuotaService quotaService;
    @Mock AuditService auditService;

    ReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        reconciliationService = new ReconciliationService(
                sessionRepository, chunkRepository, transferRepository, storagePort, quotaService, auditService);
    }

    @Test
    void reconcileAbandonedUploadSessions_abortsMultipartAndReleasesQuota() {
        Instant cutoff = Instant.now().minus(48, ChronoUnit.HOURS);
        Transfer t = Transfer.create("sender-1", "file.bin", 10000, null, PrivacyMode.STANDARD, "hash");

        UploadSession session = UploadSession.create(t.getId(), "s3-upload-123", 5000, 2);

        FileChunk chunk1 = FileChunk.create(session.getId(), 1, 5000);

        when(sessionRepository.findStaleSessions(cutoff)).thenReturn(List.of(session));
        when(transferRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(chunkRepository.findByUploadSessionIdOrderByChunkIndexAsc(session.getId())).thenReturn(List.of(chunk1));

        int count = reconciliationService.reconcileAbandonedUploadSessions(cutoff);

        assertThat(count).isEqualTo(1);
        assertThat(session.getState()).isEqualTo(UploadSessionState.ABORTED);
        assertThat(chunk1.getState()).isEqualTo(ChunkState.FAILED);

        verify(storagePort).abortMultipartUpload(t.getStorageObjectKey(), "s3-upload-123");
        verify(quotaService).releaseReservation(t.getId());
        verify(sessionRepository).save(session);
        verify(chunkRepository).save(chunk1);
        verify(auditService).record(eq(null), eq("UPLOAD_SESSION_RECONCILED_ABORTED"), eq("upload_session"), eq(session.getId()), eq("SYSTEM"));
    }

    @Test
    void verifyStorageConsistency_missingObject_marksTransferFailed() {
        Transfer t = Transfer.create("sender-1", "file.bin", 10000, null, PrivacyMode.STANDARD, "hash");
        t.markUploading();
        t.markVerifying();
        t.markStoredAndVerified("hash", Instant.now(), Instant.now().plusSeconds(86400));
        t.makeAvailableToRecipient();

        when(transferRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(storagePort.objectExists(t.getStorageObjectKey())).thenReturn(false);

        boolean consistent = reconciliationService.verifyStorageConsistency(t.getId());

        assertThat(consistent).isFalse();
        assertThat(t.getState()).isEqualTo(TransferState.FAILED);
        verify(transferRepository).save(t);
        verify(auditService).record(eq(null), eq("STORAGE_OBJECT_MISSING_DISCREPANCY"), eq("transfer"), eq(t.getId()), eq("SYSTEM"));
    }

    @Test
    void verifyStorageConsistency_objectExists_returnsTrue() {
        Transfer t = Transfer.create("sender-1", "file.bin", 10000, null, PrivacyMode.STANDARD, "hash");
        t.markUploading();
        t.markVerifying();
        t.markStoredAndVerified("hash", Instant.now(), Instant.now().plusSeconds(86400));
        t.makeAvailableToRecipient();

        when(transferRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(storagePort.objectExists(t.getStorageObjectKey())).thenReturn(true);

        boolean consistent = reconciliationService.verifyStorageConsistency(t.getId());

        assertThat(consistent).isTrue();
        assertThat(t.getState()).isEqualTo(TransferState.AVAILABLE_TO_RECIPIENT);
        verify(transferRepository, never()).save(t);
    }
}

