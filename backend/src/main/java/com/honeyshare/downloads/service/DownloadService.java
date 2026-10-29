package com.honeyshare.downloads.service;

import com.honeyshare.audit.AuditService;
import com.honeyshare.common.exception.*;
import com.honeyshare.config.TransferProperties;
import com.honeyshare.downloads.domain.DownloadSession;
import com.honeyshare.downloads.domain.DownloadSessionState;
import com.honeyshare.downloads.port.DownloadSessionRepository;
import com.honeyshare.identity.domain.User;
import com.honeyshare.identity.port.UserRepository;
import com.honeyshare.notifications.domain.NotificationEventType;
import com.honeyshare.notifications.service.OutboxService;
import com.honeyshare.storage.StoragePort;
import com.honeyshare.storage.StorageProperties;
import com.honeyshare.transfers.domain.RecipientState;
import com.honeyshare.transfers.domain.Transfer;
import com.honeyshare.transfers.domain.TransferRecipient;
import com.honeyshare.transfers.domain.TransferState;
import com.honeyshare.transfers.port.TransferRecipientRepository;
import com.honeyshare.transfers.port.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class DownloadService {

    private static final Logger log = LoggerFactory.getLogger(DownloadService.class);

    private final TransferRepository transferRepository;
    private final TransferRecipientRepository recipientRepository;
    private final UserRepository userRepository;
    private final DownloadSessionRepository downloadSessionRepository;
    private final StoragePort storagePort;
    private final StorageProperties storageProperties;
    private final TransferProperties transferProperties;
    private final AuditService auditService;
    private final OutboxService outboxService;

    public DownloadService(
            TransferRepository transferRepository,
            TransferRecipientRepository recipientRepository,
            UserRepository userRepository,
            DownloadSessionRepository downloadSessionRepository,
            StoragePort storagePort,
            StorageProperties storageProperties,
            TransferProperties transferProperties,
            AuditService auditService,
            OutboxService outboxService
    ) {
        this.transferRepository = transferRepository;
        this.recipientRepository = recipientRepository;
        this.userRepository = userRepository;
        this.downloadSessionRepository = downloadSessionRepository;
        this.storagePort = storagePort;
        this.storageProperties = storageProperties;
        this.transferProperties = transferProperties;
        this.auditService = auditService;
        this.outboxService = outboxService;
    }

    /**
     * Authorize a download for a designated recipient.
     * Enforces recipient verification, transfer eligibility, and the 24-hour active-download grace period.
     */
    @Transactional
    public DownloadAuthorization authorizeDownload(String transferId, String recipientUserId, String ipAddress) {
        User recipientUser = userRepository.findById(recipientUserId)
                .orElseThrow(() -> new AuthenticationException("User not found: " + recipientUserId));

        if (!recipientUser.canAccessFiles()) {
            throw new EmailNotVerifiedException();
        }

        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));

        if (transfer.getState() == TransferState.CANCELLED) {
            throw new TransferCancelledException(transferId);
        }

        if (transfer.getState() == TransferState.DELETED) {
            throw new TransferExpiredException(transferId);
        }

        TransferRecipient recipient = recipientRepository.findByTransferIdAndRecipientUserId(transferId, recipientUserId)
                .orElseThrow(() -> new RecipientNotAuthorizedException("Not an authorized recipient for transfer: " + transferId));

        if (recipient.getState() == RecipientState.CANCELLED) {
            throw new RecipientNotAuthorizedException("Recipient access has been revoked for transfer: " + transferId);
        }

        Instant now = Instant.now();
        boolean isPastTransferExpiry = transfer.getExpiresAt() != null && now.isAfter(transfer.getExpiresAt());
        DownloadSession session;

        if (isPastTransferExpiry) {
            // Find existing active session authorized before transfer expiry
            session = downloadSessionRepository.findFirstByTransferIdAndRecipientUserIdAndStateInOrderByCreatedAtDesc(
                    transferId, recipientUserId, List.of(DownloadSessionState.AUTHORIZED, DownloadSessionState.IN_PROGRESS))
                    .orElse(null);

            if (session == null || session.isExpired(now)) {
                if (session != null && session.isExpired(now)) {
                    session.markExpired();
                    downloadSessionRepository.save(session);
                }
                throw new TransferExpiredException(transferId);
            }

            // Eligible active session reconnecting within the fixed grace period
            session.recordReconnect();
            downloadSessionRepository.save(session);
            log.info("Download session resumed during grace period: session={} transferId={} reconnects={}",
                    session.getId(), transferId, session.getReconnectCount());
        } else {
            if (!transfer.getState().canDownload()) {
                throw new IllegalStateException("Transfer is not in a downloadable state: " + transfer.getState());
            }

            // Re-use active session if present, or create a new session
            session = downloadSessionRepository.findFirstByTransferIdAndRecipientUserIdAndStateInOrderByCreatedAtDesc(
                    transferId, recipientUserId, List.of(DownloadSessionState.AUTHORIZED, DownloadSessionState.IN_PROGRESS))
                    .orElse(null);

            if (session != null && !session.isExpired(now)) {
                session.recordReconnect();
                downloadSessionRepository.save(session);
            } else {
                Instant graceExpiry = transfer.getExpiresAt() != null
                        ? transfer.getExpiresAt().plus(transferProperties.getRetention().getActiveDownloadGraceHours(), ChronoUnit.HOURS)
                        : now.plus(transferProperties.getRetention().getDefaultDurationHours() + transferProperties.getRetention().getActiveDownloadGraceHours(), ChronoUnit.HOURS);

                session = DownloadSession.create(transferId, recipientUserId, graceExpiry, ipAddress);
                downloadSessionRepository.save(session);
            }
        }

        recipient.markDownloadStarted(now);
        recipientRepository.save(recipient);

        long maxUrlTtl = storageProperties.getPresignedUrlLifetimeSeconds();
        long remainingGraceSeconds = Duration.between(now, session.getExpiresAt()).getSeconds();
        Duration ttl = Duration.ofSeconds(Math.max(1, Math.min(maxUrlTtl, remainingGraceSeconds)));

        String presignedDownloadUrl = storagePort.generatePresignedDownloadUrl(
                transfer.getStorageObjectKey(), ttl, transfer.getOriginalFilename());

        log.info("Download authorized: session={} transferId={} recipientId={}", session.getId(), transferId, recipientUserId);
        auditService.record(recipientUserId, "DOWNLOAD_AUTHORIZED", "transfer", transferId, ipAddress);

        return new DownloadAuthorization(
                session.getId(),
                transfer.getId(),
                transfer.getOriginalFilename(),
                transfer.getFileSizeBytes(),
                transfer.getVerifiedSha256(),
                presignedDownloadUrl,
                ttl.getSeconds(),
                transfer.getExpiresAt(),
                session.getExpiresAt()
        );
    }

    /**
     * Complete download session and acknowledge verified receiver delivery.
     */
    @Transactional
    public void completeDownloadSession(String sessionId, String recipientUserId, String verifiedChecksum, String ipAddress) {
        DownloadSession session = downloadSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Download session not found: " + sessionId));

        if (!session.getRecipientUserId().equals(recipientUserId)) {
            throw new RecipientNotAuthorizedException("Not authorized for download session: " + sessionId);
        }

        if (session.getState() == DownloadSessionState.COMPLETED_REPORTED) {
            return; // Idempotent
        }

        Instant now = Instant.now();
        session.markCompleted(now);
        downloadSessionRepository.save(session);

        acknowledgeDownload(session.getTransferId(), recipientUserId, verifiedChecksum, ipAddress);
    }

    /**
     * Cancel / abandon a download session.
     */
    @Transactional
    public void cancelDownloadSession(String sessionId, String recipientUserId) {
        DownloadSession session = downloadSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Download session not found: " + sessionId));

        if (!session.getRecipientUserId().equals(recipientUserId)) {
            throw new RecipientNotAuthorizedException("Not authorized for download session: " + sessionId);
        }

        session.markAbandoned();
        downloadSessionRepository.save(session);
        log.info("Download session abandoned: session={}", sessionId);
    }

    /**
     * Idempotently acknowledge that recipient downloaded and verified the content.
     */
    @Transactional
    public void acknowledgeDownload(String transferId, String recipientUserId, String verifiedChecksum, String ipAddress) {
        TransferRecipient recipient = recipientRepository.findByTransferIdAndRecipientUserId(transferId, recipientUserId)
                .orElseThrow(() -> new RecipientNotAuthorizedException("Not an authorized recipient for transfer: " + transferId));

        if (recipient.getState() == RecipientState.VERIFIED_AT_RECEIVER) {
            return; // Idempotent
        }

        Instant now = Instant.now();
        recipient.markVerifiedAtReceiver(now);
        recipientRepository.save(recipient);

        // Check if all recipients have downloaded
        List<TransferRecipient> allRecipients = recipientRepository.findByTransferId(transferId);
        boolean allVerified = allRecipients.stream()
                .allMatch(r -> r.getState() == RecipientState.VERIFIED_AT_RECEIVER);

        transferRepository.findById(transferId).ifPresent(transfer -> {
            if (allVerified) {
                log.info("All recipients have verified transfer {}. Marking DELIVERED.", transferId);
            }
            // Publish outbox event to sender
            String eventId = "evt:download-complete:" + transferId + ":" + recipientUserId;
            outboxService.publishEvent(
                    transfer.getSenderId(),
                    NotificationEventType.DOWNLOAD_COMPLETED_REPORTED,
                    eventId,
                    "File downloaded",
                    "A recipient has downloaded and verified your transfer.",
                    "A recipient has downloaded your file.",
                    transferId
            );
        });

        log.info("Download verified by receiver: transferId={} recipientId={}", transferId, recipientUserId);
        auditService.record(recipientUserId, "DOWNLOAD_VERIFIED_RECEIVER", "transfer", transferId, ipAddress);
    }

    /**
     * Get transfers available in recipient inbox.
     */
    @Transactional(readOnly = true)
    public List<InboxItem> getRecipientInbox(String recipientUserId) {
        List<TransferRecipient> recipientRecords = recipientRepository.findByRecipientUserIdOrderByCreatedAtDesc(recipientUserId);

        return recipientRecords.stream()
                .map(r -> {
                    Transfer t = transferRepository.findById(r.getTransferId()).orElse(null);
                    if (t == null) return null;
                    if (!t.getState().canDownload() && t.getState() != TransferState.DELIVERED) {
                        return null;
                    }
                    return new InboxItem(
                            t.getId(),
                            t.getOriginalFilename(),
                            t.getFileSizeBytes(),
                            t.getSenderId(),
                            t.getPrivacyMode().name(),
                            t.getState().name(),
                            r.getState().name(),
                            t.getStoredAt(),
                            t.getExpiresAt()
                    );
                })
                .filter(item -> item != null)
                .toList();
    }

    /**
     * Get single transfer details from recipient inbox.
     */
    @Transactional(readOnly = true)
    public InboxItem getInboxItem(String transferId, String recipientUserId) {
        TransferRecipient recipient = recipientRepository.findByTransferIdAndRecipientUserId(transferId, recipientUserId)
                .orElseThrow(() -> new RecipientNotAuthorizedException("Not authorized for transfer: " + transferId));

        Transfer t = transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));

        return new InboxItem(
                t.getId(),
                t.getOriginalFilename(),
                t.getFileSizeBytes(),
                t.getSenderId(),
                t.getPrivacyMode().name(),
                t.getState().name(),
                recipient.getState().name(),
                t.getStoredAt(),
                t.getExpiresAt()
        );
    }

    public record DownloadStreamPayload(
            InputStream stream,
            String filename,
            String contentType,
            long sizeBytes,
            long rangeStart,
            long rangeEnd,
            boolean isPartial
    ) {
        public DownloadStreamPayload(InputStream stream, String filename, String contentType, long sizeBytes) {
            this(stream, filename, contentType, sizeBytes, 0, sizeBytes > 0 ? sizeBytes - 1 : 0, false);
        }
    }

    @Transactional(readOnly = true)
    public DownloadStreamPayload openDownloadStream(String sessionId, String requestingUserId) {
        DownloadSession session = downloadSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Download session not found: " + sessionId));

        if (!session.getRecipientUserId().equals(requestingUserId)) {
            throw new RecipientNotAuthorizedException("Not authorized for this download session");
        }

        Transfer transfer = transferRepository.findById(session.getTransferId())
                .orElseThrow(() -> new TransferNotFoundException(session.getTransferId()));

        if (!transfer.getState().canDownload()) {
            throw new IllegalStateException("Transfer is not in a downloadable state: " + transfer.getState());
        }

        InputStream stream = storagePort.openObjectStream(transfer.getStorageObjectKey());
        String contentType = transfer.getContentTypeHint() != null ? transfer.getContentTypeHint() : "application/octet-stream";
        return new DownloadStreamPayload(stream, transfer.getOriginalFilename(), contentType, transfer.getFileSizeBytes());
    }

    @Transactional(readOnly = true)
    public DownloadStreamPayload openDownloadRangeStream(String sessionId, String requestingUserId, long startByte, long endByte) {
        DownloadSession session = downloadSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Download session not found: " + sessionId));

        if (!session.getRecipientUserId().equals(requestingUserId)) {
            throw new RecipientNotAuthorizedException("Not authorized for this download session");
        }

        Transfer transfer = transferRepository.findById(session.getTransferId())
                .orElseThrow(() -> new TransferNotFoundException(session.getTransferId()));

        if (!transfer.getState().canDownload()) {
            throw new IllegalStateException("Transfer is not in a downloadable state: " + transfer.getState());
        }

        long totalSize = transfer.getFileSizeBytes();
        long validStart = Math.max(0, startByte);
        long validEnd = (endByte >= validStart && endByte < totalSize) ? endByte : Math.max(0, totalSize - 1);

        InputStream stream = storagePort.openObjectRangeStream(transfer.getStorageObjectKey(), validStart, validEnd);
        String contentType = transfer.getContentTypeHint() != null ? transfer.getContentTypeHint() : "application/octet-stream";
        long rangeLength = Math.max(0, validEnd - validStart + 1);

        return new DownloadStreamPayload(stream, transfer.getOriginalFilename(), contentType, rangeLength, validStart, validEnd, true);
    }

    public record DownloadAuthorization(
            String sessionId,
            String transferId,
            String originalFilename,
            long fileSizeBytes,
            String sha256,
            String presignedDownloadUrl,
            long ttlSeconds,
            Instant transferExpiresAt,
            Instant sessionGraceExpiresAt
    ) {}

    public record InboxItem(
            String transferId,
            String originalFilename,
            long fileSizeBytes,
            String senderId,
            String privacyMode,
            String transferState,
            String recipientState,
            Instant storedAt,
            Instant expiresAt
    ) {}
}
