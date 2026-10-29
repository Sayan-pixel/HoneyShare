package com.honeyshare.transfers.service;

import com.honeyshare.audit.AuditService;
import com.honeyshare.quotas.QuotaService;
import com.honeyshare.storage.StoragePort;
import com.honeyshare.transfers.domain.ChunkState;
import com.honeyshare.transfers.domain.FileChunk;
import com.honeyshare.transfers.domain.Transfer;
import com.honeyshare.transfers.domain.UploadSession;
import com.honeyshare.transfers.port.FileChunkRepository;
import com.honeyshare.transfers.port.TransferRepository;
import com.honeyshare.transfers.port.UploadSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Background reconciliation service detecting and repairing discrepancies
 * between MySQL metadata and object storage.
 * Complies with HoneyShare Specification Section 11 & Section 18 (Phase 3).
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final UploadSessionRepository sessionRepository;
    private final FileChunkRepository chunkRepository;
    private final TransferRepository transferRepository;
    private final StoragePort storagePort;
    private final QuotaService quotaService;
    private final AuditService auditService;

    public ReconciliationService(
            UploadSessionRepository sessionRepository,
            FileChunkRepository chunkRepository,
            TransferRepository transferRepository,
            StoragePort storagePort,
            QuotaService quotaService,
            AuditService auditService
    ) {
        this.sessionRepository = sessionRepository;
        this.chunkRepository = chunkRepository;
        this.transferRepository = transferRepository;
        this.storagePort = storagePort;
        this.quotaService = quotaService;
        this.auditService = auditService;
    }

    /**
     * Periodic scheduled worker to reconcile abandoned upload sessions (default 48h timeout).
     */
    @Scheduled(fixedDelayString = "${honeyshare.jobs.reconciliation-check-interval-ms:300000}")
    public void runReconciliationWorker() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(48));
        int reconciled = reconcileAbandonedUploadSessions(cutoff);
        if (reconciled > 0) {
            log.info("Reconciliation worker completed: abortedSessions={}", reconciled);
        }
    }

    /**
     * Abort abandoned multipart upload sessions, clean up temporary storage parts,
     * and release unused quota reservations.
     */
    @Transactional
    public int reconcileAbandonedUploadSessions(Instant cutoff) {
        List<UploadSession> staleSessions = sessionRepository.findStaleSessions(cutoff);
        Instant now = Instant.now();
        int count = 0;

        for (UploadSession session : staleSessions) {
            Transfer transfer = transferRepository.findById(session.getTransferId()).orElse(null);
            if (transfer == null) continue;

            // Abort multipart upload on object storage
            if (session.getStorageUploadId() != null && transfer.getStorageObjectKey() != null) {
                try {
                    storagePort.abortMultipartUpload(transfer.getStorageObjectKey(), session.getStorageUploadId());
                    log.info("Aborted remote multipart upload on storage: transferId={} uploadId={}",
                            transfer.getId(), session.getStorageUploadId());
                } catch (Exception e) {
                    log.error("Failed to abort multipart upload for session {}: {}", session.getId(), e.getMessage());
                }
            }

            session.markAborted(now);
            sessionRepository.save(session);

            // Mark any pending/uploading chunks as FAILED
            List<FileChunk> chunks = chunkRepository.findByUploadSessionIdOrderByChunkIndexAsc(session.getId());
            for (FileChunk chunk : chunks) {
                if (chunk.getState() == ChunkState.PENDING || chunk.getState() == ChunkState.UPLOADING) {
                    chunk.markFailed();
                    chunkRepository.save(chunk);
                }
            }

            // Release active quota reservation
            quotaService.releaseReservation(transfer.getId());

            log.info("Reconciled abandoned upload session: session={} transferId={}", session.getId(), transfer.getId());
            auditService.record(null, "UPLOAD_SESSION_RECONCILED_ABORTED", "upload_session", session.getId(), "SYSTEM");
            count++;
        }

        return count;
    }

    /**
     * Verify that an object declared STORED_AND_VERIFIED actually exists in storage.
     * Prevents serving false-success / missing downloads.
     */
    @Transactional
    public boolean verifyStorageConsistency(String transferId) {
        Transfer transfer = transferRepository.findById(transferId).orElse(null);
        if (transfer == null) return false;

        boolean exists = storagePort.objectExists(transfer.getStorageObjectKey());
        if (!exists) {
            log.error("CRITICAL DATA DISCREPANCY: Transfer {} marked {} but object {} is missing from storage!",
                    transfer.getId(), transfer.getState(), transfer.getStorageObjectKey());
            transfer.markFailed();
            transferRepository.save(transfer);
            auditService.record(null, "STORAGE_OBJECT_MISSING_DISCREPANCY", "transfer", transfer.getId(), "SYSTEM");
            return false;
        }

        return true;
    }
}

