package com.honeyshare.transfers.service;

import com.honeyshare.audit.AuditService;
import com.honeyshare.common.exception.ChecksumMismatchException;
import com.honeyshare.common.exception.RecipientNotAuthorizedException;
import com.honeyshare.common.exception.TransferNotFoundException;
import com.honeyshare.config.TransferProperties;
import com.honeyshare.integrity.ChecksumService;
import com.honeyshare.quotas.QuotaService;
import com.honeyshare.storage.PartETag;
import com.honeyshare.storage.StoragePort;
import com.honeyshare.storage.StorageProperties;
import com.honeyshare.transfers.domain.*;
import com.honeyshare.transfers.port.*;
import com.honeyshare.notifications.domain.NotificationEventType;
import com.honeyshare.notifications.service.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
public class UploadSessionService {

    private static final Logger log = LoggerFactory.getLogger(UploadSessionService.class);

    private final TransferRepository transferRepository;
    private final UploadSessionRepository sessionRepository;
    private final FileChunkRepository chunkRepository;
    private final TransferRecipientRepository recipientRepository;
    private final StoragePort storagePort;
    private final StorageProperties storageProperties;
    private final TransferProperties transferProperties;
    private final ChecksumService checksumService;
    private final QuotaService quotaService;
    private final AuditService auditService;
    private final OutboxService outboxService;

    public UploadSessionService(
            TransferRepository transferRepository,
            UploadSessionRepository sessionRepository,
            FileChunkRepository chunkRepository,
            TransferRecipientRepository recipientRepository,
            StoragePort storagePort,
            StorageProperties storageProperties,
            TransferProperties transferProperties,
            ChecksumService checksumService,
            QuotaService quotaService,
            AuditService auditService,
            OutboxService outboxService
    ) {
        this.transferRepository = transferRepository;
        this.sessionRepository = sessionRepository;
        this.chunkRepository = chunkRepository;
        this.recipientRepository = recipientRepository;
        this.storagePort = storagePort;
        this.storageProperties = storageProperties;
        this.transferProperties = transferProperties;
        this.checksumService = checksumService;
        this.quotaService = quotaService;
        this.auditService = auditService;
        this.outboxService = outboxService;
    }

    /**
     * Start a new resumable upload session on object storage and database.
     */
    @Transactional
    public UploadSession createUploadSession(String transferId, String senderUserId, String ipAddress) {
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));

        if (!transfer.getSenderId().equals(senderUserId)) {
            throw new RecipientNotAuthorizedException("Only the sender can initiate an upload session");
        }

        if (transfer.getState().isTerminal()) {
            throw new IllegalStateException("Transfer is in terminal state: " + transfer.getState());
        }

        long fileSize = transfer.getFileSizeBytes();
        long chunkSize = transferProperties.getUpload().getChunkSizeBytes();
        int chunkCount = fileSize == 0 ? 1 : (int) Math.ceil((double) fileSize / chunkSize);

        // 1. Initiate multipart on S3/MinIO
        String storageUploadId = storagePort.initiateMultipartUpload(
                transfer.getStorageObjectKey(), transfer.getContentTypeHint());

        // 2. Persist UploadSession
        UploadSession session = UploadSession.create(transferId, storageUploadId, chunkSize, chunkCount);
        session.markActive(Instant.now());
        sessionRepository.save(session);

        // 3. Pre-create chunk manifest entries
        long remaining = fileSize;
        for (int i = 1; i <= chunkCount; i++) {
            long currentChunkBytes = (fileSize == 0) ? 0 : Math.min(remaining, chunkSize);
            FileChunk chunk = FileChunk.create(session.getId(), i, currentChunkBytes);
            chunkRepository.save(chunk);
            remaining -= currentChunkBytes;
        }

        // 4. Update transfer state
        transfer.markUploading();
        transferRepository.save(transfer);

        log.info("Upload session initiated: id={} transferId={} chunks={}", session.getId(), transferId, chunkCount);
        auditService.record(senderUserId, "UPLOAD_SESSION_INITIATED", "upload_session", session.getId(), ipAddress);

        return session;
    }

    /**
     * Get the authoritative progress manifest for an upload session.
     * Clients reconcile against this to resume without re-uploading completed parts.
     */
    @Transactional(readOnly = true)
    public UploadManifest getManifest(String sessionId, String senderUserId) {
        UploadSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Upload session not found: " + sessionId));

        Transfer transfer = transferRepository.findById(session.getTransferId())
                .orElseThrow(() -> new TransferNotFoundException(session.getTransferId()));

        if (!transfer.getSenderId().equals(senderUserId)) {
            throw new RecipientNotAuthorizedException("Not authorized to view manifest for session: " + sessionId);
        }

        List<FileChunk> chunks = chunkRepository.findByUploadSessionIdOrderByChunkIndexAsc(sessionId);
        List<ChunkManifestEntry> entries = chunks.stream()
                .map(c -> new ChunkManifestEntry(
                        c.getChunkIndex(), c.getState(), c.getByteLength(), c.getStoragePartId(), c.getChecksumValue()))
                .toList();

        return new UploadManifest(
                session.getId(),
                transfer.getId(),
                session.getState(),
                session.getChunkSizeBytes(),
                session.getExpectedChunkCount(),
                entries
        );
    }

    /**
     * Authorize upload of a specific part by issuing a short-lived pre-signed URL.
     */
    @Transactional
    public PartUploadAuthorization authorizePart(String sessionId, int partIndex, String senderUserId) {
        UploadSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Upload session not found: " + sessionId));

        Transfer transfer = transferRepository.findById(session.getTransferId())
                .orElseThrow(() -> new TransferNotFoundException(session.getTransferId()));

        if (!transfer.getSenderId().equals(senderUserId)) {
            throw new RecipientNotAuthorizedException("Not authorized");
        }

        int normalizedPartIndex = (partIndex <= 0) ? partIndex + 1 : partIndex;
        FileChunk chunk = chunkRepository.findByUploadSessionIdAndChunkIndex(sessionId, normalizedPartIndex)
                .orElseThrow(() -> new IllegalArgumentException("Invalid part index: " + partIndex));

        Duration ttl = Duration.ofSeconds(storageProperties.getPresignedUrlLifetimeSeconds());
        String presignedUrl = storagePort.generatePresignedUploadPartUrl(
                transfer.getStorageObjectKey(), session.getStorageUploadId(), normalizedPartIndex, ttl);

        chunk.markUploading();
        chunkRepository.save(chunk);
        session.touch(Instant.now());
        sessionRepository.save(session);

        return new PartUploadAuthorization(sessionId, normalizedPartIndex, presignedUrl, ttl.getSeconds());
    }

    /**
     * Directly stream an uploaded part to storage and commit it atomically.
     */
    @Transactional
    public FileChunk uploadAndCommitPart(
            String sessionId, int partIndex, InputStream stream, long contentLength, String senderUserId
    ) {
        UploadSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Upload session not found: " + sessionId));

        Transfer transfer = transferRepository.findById(session.getTransferId())
                .orElseThrow(() -> new TransferNotFoundException(session.getTransferId()));

        if (!transfer.getSenderId().equals(senderUserId)) {
            throw new RecipientNotAuthorizedException("Not authorized");
        }

        int normalizedPartIndex = (partIndex <= 0) ? partIndex + 1 : partIndex;
        FileChunk chunk = chunkRepository.findByUploadSessionIdAndChunkIndex(sessionId, normalizedPartIndex)
                .orElseThrow(() -> new IllegalArgumentException("Invalid part index: " + partIndex));

        String eTag = storagePort.uploadPart(
                transfer.getStorageObjectKey(),
                session.getStorageUploadId(),
                normalizedPartIndex,
                stream,
                contentLength
        );

        chunk.markCommitted(eTag, null, Instant.now());
        chunkRepository.save(chunk);

        session.touch(Instant.now());
        sessionRepository.save(session);

        log.info("Chunk uploaded & committed via stream: session={} part={} etag={}", sessionId, normalizedPartIndex, eTag);
        return chunk;
    }

    /**
     * Idempotently commit a completed part.
     */
    @Transactional
    public FileChunk commitPart(
            String sessionId, int partIndex, String partEtag, String checksumValue, String senderUserId
    ) {
        UploadSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Upload session not found: " + sessionId));

        Transfer transfer = transferRepository.findById(session.getTransferId())
                .orElseThrow(() -> new TransferNotFoundException(session.getTransferId()));

        if (!transfer.getSenderId().equals(senderUserId)) {
            throw new RecipientNotAuthorizedException("Not authorized");
        }

        int normalizedPartIndex = (partIndex <= 0) ? partIndex + 1 : partIndex;
        FileChunk chunk = chunkRepository.findByUploadSessionIdAndChunkIndex(sessionId, normalizedPartIndex)
                .orElseThrow(() -> new IllegalArgumentException("Invalid part index: " + partIndex));

        // Idempotent retry check: if already committed with same ETag, return directly
        if (chunk.getState() == ChunkState.COMMITTED && partEtag.equals(chunk.getStoragePartId())) {
            return chunk;
        }

        Instant now = Instant.now();
        chunk.markCommitted(partEtag, checksumValue, now);
        chunkRepository.save(chunk);

        session.touch(now);
        sessionRepository.save(session);

        log.debug("Committed chunk: session={} part={} etag={}", sessionId, partIndex, partEtag);
        return chunk;
    }

    /**
     * Complete the upload session, assembling the multipart object in storage
     * and performing streaming full-file SHA-256 verification.
     */
    @Transactional(noRollbackFor = {IllegalStateException.class, ChecksumMismatchException.class})
    public DurableStorageReceipt completeUploadSession(String sessionId, String senderUserId, String ipAddress) {
        UploadSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Upload session not found: " + sessionId));

        Transfer transfer = transferRepository.findById(session.getTransferId())
                .orElseThrow(() -> new TransferNotFoundException(session.getTransferId()));

        if (!transfer.getSenderId().equals(senderUserId)) {
            throw new RecipientNotAuthorizedException("Not authorized");
        }

        // 1. Validate all chunks are committed
        List<FileChunk> chunks = chunkRepository.findByUploadSessionIdOrderByChunkIndexAsc(sessionId);
        if (chunks.size() != session.getExpectedChunkCount()) {
            throw new IllegalStateException("Missing chunks: found " + chunks.size() + ", expected " + session.getExpectedChunkCount());
        }

        List<PartETag> partEtags = new ArrayList<>();
        for (FileChunk c : chunks) {
            if (c.getState() != ChunkState.COMMITTED && c.getState() != ChunkState.VERIFIED) {
                throw new IllegalStateException("Part " + c.getChunkIndex() + " is not committed (state=" + c.getState() + ")");
            }
            partEtags.add(new PartETag(c.getChunkIndex(), c.getStoragePartId()));
        }

        Instant now = Instant.now();
        session.markCompleting(now);
        transfer.markVerifying();
        transferRepository.save(transfer);

        // 2. Complete multipart assembly on storage
        storagePort.completeMultipartUpload(transfer.getStorageObjectKey(), session.getStorageUploadId(), partEtags);

        // 3. Size verification
        long actualSize = storagePort.getObjectSize(transfer.getStorageObjectKey());
        if (actualSize != transfer.getFileSizeBytes()) {
            session.markFailed("SIZE_MISMATCH", now);
            sessionRepository.save(session);
            transfer.markFailed();
            transferRepository.save(transfer);
            quotaService.releaseReservation(transfer.getId());
            throw new IllegalStateException("Storage size mismatch: expected " + transfer.getFileSizeBytes() + ", actual " + actualSize);
        }

        // 4. Streaming SHA-256 verification
        String actualSha256;
        try (InputStream stream = storagePort.openObjectStream(transfer.getStorageObjectKey())) {
            actualSha256 = checksumService.calculateSha256(stream);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read assembled object for checksum verification", e);
        }

        if (!checksumService.verifySha256(actualSha256, transfer.getExpectedSha256())) {
            session.markFailed("CHECKSUM_MISMATCH", now);
            sessionRepository.save(session);
            transfer.markFailed();
            transferRepository.save(transfer);
            quotaService.releaseReservation(transfer.getId());
            throw new ChecksumMismatchException(transfer.getExpectedSha256(), actualSha256);
        }

        // 5. Success state transitions
        Instant expiry = now.plus(transferProperties.getRetention().getDefaultDurationHours(), ChronoUnit.HOURS);
        transfer.markStoredAndVerified(actualSha256, now, expiry);
        transfer.makeAvailableToRecipient();
        transferRepository.save(transfer);

        session.markCompleted(now);
        sessionRepository.save(session);

        for (FileChunk c : chunks) {
            c.markVerified(now);
            chunkRepository.save(c);
        }

        // Commit quota reservation
        quotaService.commitReservation(transfer.getId());

        // Make transfer available to email-verified recipients and publish notification
        List<TransferRecipient> recipients = recipientRepository.findByTransferId(transfer.getId());
        for (TransferRecipient r : recipients) {
            if (r.getState() == RecipientState.AVAILABLE || r.getState() == RecipientState.AWAITING_EMAIL_VERIFICATION) {
                r.markAvailable(now);
                recipientRepository.save(r);
            }

            // Publish durable outbox notification for recipient
            String eventId = "evt:transfer-stored:" + transfer.getId() + ":" + r.getRecipientUserId();
            outboxService.publishEvent(
                    r.getRecipientUserId(),
                    NotificationEventType.TRANSFER_RECEIVED,
                    eventId,
                    "New file available",
                    "A file has been shared with you in HoneyShare.",
                    "You have a new file. Open HoneyShare to view it.",
                    transfer.getId()
            );
        }

        log.info("Upload session completed & verified: session={} transfer={} sha256={}",
                sessionId, transfer.getId(), actualSha256);
        auditService.record(senderUserId, "TRANSFER_STORED_AND_VERIFIED", "transfer", transfer.getId(), ipAddress);

        return new DurableStorageReceipt(
                transfer.getId(),
                transfer.getStorageObjectKey(),
                transfer.getFileSizeBytes(),
                actualSha256,
                transfer.getStateVersion(),
                now
        );
    }

    // ----------------------------------------------------------------
    // DTO records
    // ----------------------------------------------------------------

    public record ChunkManifestEntry(
            int chunkIndex, ChunkState state, long byteLength, String storagePartId, String checksumValue
    ) {}

    public record UploadManifest(
            String sessionId,
            String transferId,
            UploadSessionState state,
            long chunkSizeBytes,
            int expectedChunkCount,
            List<ChunkManifestEntry> chunks
    ) {}

    public record PartUploadAuthorization(
            String sessionId, int partIndex, String presignedUploadUrl, long ttlSeconds
    ) {}

    public record DurableStorageReceipt(
            String transferId,
            String storageObjectKey,
            long fileSizeBytes,
            String verifiedSha256,
            long stateVersion,
            Instant verifiedAt
    ) {}
}

