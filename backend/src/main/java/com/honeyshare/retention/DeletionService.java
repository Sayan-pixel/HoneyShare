package com.honeyshare.retention;

import com.honeyshare.audit.AuditService;
import com.honeyshare.config.TransferProperties;
import com.honeyshare.downloads.port.DownloadSessionRepository;
import com.honeyshare.quotas.QuotaService;
import com.honeyshare.storage.StoragePort;
import com.honeyshare.transfers.domain.Transfer;
import com.honeyshare.transfers.domain.TransferState;
import com.honeyshare.transfers.port.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Asynchronous deletion engine for expired and cancelled transfers.
 * Complies with HoneyShare Specification Section 3.4, Section 5 (FR-RET-06, FR-RET-07), and Section 8.4.
 *
 * Physical storage deletion occurs asynchronously and idempotently:
 * 1. Transfer transitions to DELETION_PENDING.
 * 2. Remote object is deleted via StoragePort.
 * 3. Transfer transitions to DELETED only AFTER storage deletion is confirmed.
 * 4. Stored quota is released only upon confirmed deletion.
 */
@Service
public class DeletionService {

    private static final Logger log = LoggerFactory.getLogger(DeletionService.class);

    private final TransferRepository transferRepository;
    private final DownloadSessionRepository downloadSessionRepository;
    private final StoragePort storagePort;
    private final QuotaService quotaService;
    private final TransferProperties transferProperties;
    private final AuditService auditService;

    public DeletionService(
            TransferRepository transferRepository,
            DownloadSessionRepository downloadSessionRepository,
            StoragePort storagePort,
            QuotaService quotaService,
            TransferProperties transferProperties,
            AuditService auditService
    ) {
        this.transferRepository = transferRepository;
        this.downloadSessionRepository = downloadSessionRepository;
        this.storagePort = storagePort;
        this.quotaService = quotaService;
        this.transferProperties = transferProperties;
        this.auditService = auditService;
    }

    /**
     * Periodic scheduled worker to process pending deletions.
     */
    @Scheduled(fixedDelayString = "${honeyshare.jobs.deletion-check-interval-ms:60000}")
    public void runDeletionWorker() {
        processEligibleDeletions(Instant.now());
    }

    /**
     * Process all transfers that are eligible for physical deletion:
     * - CANCELLED transfers with no active download sessions
     * - EXPIRED transfers where the 24-hour grace period has fully elapsed and no active sessions exist
     * - Previously interrupted DELETION_PENDING transfers
     */
    @Transactional
    public List<String> processEligibleDeletions(Instant now) {
        Instant graceDeadline = now.minus(Duration.ofHours(transferProperties.getRetention().getActiveDownloadGraceHours()));
        List<Transfer> candidates = transferRepository.findTransfersEligibleForDeletion(graceDeadline);
        List<Transfer> pending = transferRepository.findByState(TransferState.DELETION_PENDING);

        List<Transfer> toProcess = new ArrayList<>(candidates);
        for (Transfer p : pending) {
            if (!toProcess.contains(p)) {
                toProcess.add(p);
            }
        }

        List<String> successfullyDeleted = new ArrayList<>();

        for (Transfer transfer : toProcess) {
            // Check if any active download session is still running within its grace period
            boolean hasActiveDownloads = downloadSessionRepository.hasActiveUnexpiredSessions(transfer.getId(), now);
            if (hasActiveDownloads) {
                log.debug("Transfer {} has active download session in grace period; deferring deletion", transfer.getId());
                continue;
            }

            boolean success = deleteTransferContent(transfer, now);
            if (success) {
                successfullyDeleted.add(transfer.getId());
            }
        }

        return successfullyDeleted;
    }

    /**
     * Execute confirmed physical deletion for a single transfer.
     */
    @Transactional
    public boolean deleteTransferContent(Transfer transfer, Instant now) {
        if (transfer.getState() == TransferState.DELETED) {
            return true; // Already deleted
        }

        // 1. Mark DELETION_PENDING
        if (transfer.getState() != TransferState.DELETION_PENDING) {
            transfer.markDeletionPending(now);
            transferRepository.save(transfer);
        }

        // 2. Perform object storage deletion
        String objectKey = transfer.getStorageObjectKey();
        boolean storageDeleted = true;
        if (objectKey != null && !objectKey.isBlank()) {
            try {
                storageDeleted = storagePort.deleteObject(objectKey);
            } catch (Exception e) {
                log.error("Failed to delete object in storage for transfer {}: {}", transfer.getId(), e.getMessage());
                storageDeleted = false;
            }
        }

        if (!storageDeleted) {
            log.warn("Storage deletion not confirmed for transfer {}. Remaining in DELETION_PENDING for retry.", transfer.getId());
            return false;
        }

        // 3. Mark DELETED and release committed quota
        transfer.markDeleted(now);
        transferRepository.save(transfer);

        quotaService.releaseCommittedReservation(transfer.getId());

        log.info("Transfer content deleted and quota released: transferId={} senderId={} size={}",
                transfer.getId(), transfer.getSenderId(), transfer.getFileSizeBytes());
        auditService.record(null, "TRANSFER_DELETED", "transfer", transfer.getId(), "SYSTEM");

        return true;
    }
}

