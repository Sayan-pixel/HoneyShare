package com.honeyshare.transfers.domain;

import com.honeyshare.common.UlidGenerator;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Manages chunked/multipart upload progress for a transfer.
 */
@Entity
@Table(name = "upload_sessions")
public class UploadSession {

    @Id
    @Column(length = 26, nullable = false, updatable = false)
    private String id;

    @Column(name = "transfer_id", length = 26, nullable = false)
    private String transferId;

    @Column(name = "storage_upload_id", length = 256)
    private String storageUploadId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 30)
    private UploadSessionState state;

    @Column(name = "chunk_size_bytes", nullable = false)
    private long chunkSizeBytes;

    @Column(name = "expected_chunk_count", nullable = false)
    private int expectedChunkCount;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected UploadSession() {}

    public static UploadSession create(
            String transferId,
            String storageUploadId,
            long chunkSizeBytes,
            int expectedChunkCount
    ) {
        UploadSession s = new UploadSession();
        s.id = UlidGenerator.generate();
        s.transferId = transferId;
        s.storageUploadId = storageUploadId;
        s.chunkSizeBytes = chunkSizeBytes;
        s.expectedChunkCount = expectedChunkCount;
        s.state = UploadSessionState.INITIATED;
        Instant now = Instant.now();
        s.createdAt = now;
        s.lastActivityAt = now;
        return s;
    }

    public void markActive(Instant now) {
        this.state = UploadSessionState.ACTIVE;
        this.lastActivityAt = now;
    }

    public void markPaused(Instant now) {
        this.state = UploadSessionState.PAUSED;
        this.lastActivityAt = now;
    }

    public void markCompleting(Instant now) {
        this.state = UploadSessionState.COMPLETING;
        this.lastActivityAt = now;
    }

    public void markVerifying(Instant now) {
        this.state = UploadSessionState.VERIFYING;
        this.lastActivityAt = now;
    }

    public void markCompleted(Instant now) {
        this.state = UploadSessionState.COMPLETED;
        this.completedAt = now;
        this.lastActivityAt = now;
    }

    public void markAborted(Instant now) {
        this.state = UploadSessionState.ABORTED;
        this.lastActivityAt = now;
    }

    public void markFailed(String failureCode, Instant now) {
        this.state = UploadSessionState.FAILED;
        this.failureCode = failureCode;
        this.lastActivityAt = now;
    }

    public void touch(Instant now) {
        this.lastActivityAt = now;
    }

    public String getId() { return id; }
    public String getTransferId() { return transferId; }
    public String getStorageUploadId() { return storageUploadId; }
    public UploadSessionState getState() { return state; }
    public long getChunkSizeBytes() { return chunkSizeBytes; }
    public int getExpectedChunkCount() { return expectedChunkCount; }
    public String getFailureCode() { return failureCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastActivityAt() { return lastActivityAt; }
    public Instant getCompletedAt() { return completedAt; }
}

