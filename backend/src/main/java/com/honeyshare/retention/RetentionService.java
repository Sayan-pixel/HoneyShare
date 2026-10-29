package com.honeyshare.retention;

import com.honeyshare.audit.AuditService;
import com.honeyshare.downloads.domain.DownloadSession;
import com.honeyshare.downloads.port.DownloadSessionRepository;
import com.honeyshare.notifications.domain.NotificationEventType;
import com.honeyshare.notifications.service.OutboxService;
import com.honeyshare.transfers.domain.RecipientState;
import com.honeyshare.transfers.domain.Transfer;
import com.honeyshare.transfers.domain.TransferRecipient;
import com.honeyshare.transfers.port.TransferRecipientRepository;
import com.honeyshare.transfers.port.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Enforces the retention lifecycle for transfers and download sessions.
 * Complies with HoneyShare Specification Section 3.4, 5 (FR-RET-01, FR-RET-04), and Section 8.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final TransferRepository transferRepository;
    private final TransferRecipientRepository recipientRepository;
    private final DownloadSessionRepository downloadSessionRepository;
    private final AuditService auditService;
    private final OutboxService outboxService;

    public RetentionService(
            TransferRepository transferRepository,
            TransferRecipientRepository recipientRepository,
            DownloadSessionRepository downloadSessionRepository,
            AuditService auditService,
            OutboxService outboxService
    ) {
        this.transferRepository = transferRepository;
        this.recipientRepository = recipientRepository;
        this.downloadSessionRepository = downloadSessionRepository;
        this.auditService = auditService;
        this.outboxService = outboxService;
    }

    /**
     * Periodic scheduled worker to evaluate transfer expirations and session grace-period timeouts.
     */
    @Scheduled(fixedDelayString = "${honeyshare.jobs.retention-check-interval-ms:60000}")
    public void runRetentionWorker() {
        Instant now = Instant.now();
        int expiredTransfers = expireTransfers(now);
        int expiredSessions = expireActiveDownloadSessions(now);
        if (expiredTransfers > 0 || expiredSessions > 0) {
            log.info("Retention worker completed: expiredTransfers={} expiredSessions={}", expiredTransfers, expiredSessions);
        }
    }

    /**
     * Finds transfers past their expiration deadline and transitions them to EXPIRED.
     * New download sessions are blocked as soon as a transfer is EXPIRED.
     */
    @Transactional
    public int expireTransfers(Instant now) {
        List<Transfer> expired = transferRepository.findExpiredTransfers(now);
        int count = 0;

        for (Transfer transfer : expired) {
            transfer.markExpired(now);
            transferRepository.save(transfer);

            // Update associated recipient states to EXPIRED if not already downloaded or cancelled
            List<TransferRecipient> recipients = recipientRepository.findByTransferId(transfer.getId());
            for (TransferRecipient r : recipients) {
                if (r.getState() == RecipientState.AVAILABLE ||
                    r.getState() == RecipientState.AWAITING_EMAIL_VERIFICATION ||
                    r.getState() == RecipientState.DOWNLOAD_STARTED) {
                    r.markExpired();
                    recipientRepository.save(r);
                }

                String eventId = "evt:transfer-expired:" + transfer.getId() + ":" + r.getRecipientUserId();
                outboxService.publishEvent(
                        r.getRecipientUserId(),
                        NotificationEventType.TRANSFER_EXPIRED,
                        eventId,
                        "Transfer expired",
                        "A file transfer shared with you has expired according to retention policy.",
                        "A transfer has reached its expiration time.",
                        transfer.getId()
                );
            }

            log.info("Transfer expired by retention policy: transferId={} expiresAt={}", transfer.getId(), transfer.getExpiresAt());
            auditService.record(null, "TRANSFER_EXPIRED", "transfer", transfer.getId(), "SYSTEM");
            count++;
        }

        return count;
    }

    /**
     * Finds active download sessions that have exceeded their 24-hour grace period deadline
     * and marks them EXPIRED.
     */
    @Transactional
    public int expireActiveDownloadSessions(Instant now) {
        List<DownloadSession> expiredSessions = downloadSessionRepository.findActiveExpiredSessions(now);
        for (DownloadSession session : expiredSessions) {
            session.markExpired();
            downloadSessionRepository.save(session);
            log.info("Download session grace period expired: sessionId={} transferId={}", session.getId(), session.getTransferId());
        }
        return expiredSessions.size();
    }
}

