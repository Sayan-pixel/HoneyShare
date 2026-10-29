package com.honeyshare.transfers.domain;

import com.honeyshare.common.UlidGenerator;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Authoritative record of a logical file transfer.
 * File content itself is stored in object storage (identified by storageObjectKey).
 */
@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    @Column(length = 26, nullable = false, updatable = false)
    private String id;

    @Column(name = "sender_id", length = 26, nullable = false)
    private String senderId;

    @Column(name = "original_filename", length = 255, nullable = false)
    private String originalFilename;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @Column(name = "content_type_hint", length = 128)
    private String contentTypeHint;

    @Enumerated(EnumType.STRING)
    @Column(name = "privacy_mode", nullable = false, length = 20)
    private PrivacyMode privacyMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 30)
    private TransferState state;

    @Column(name = "storage_object_key", length = 512)
    private String storageObjectKey;

    @Column(name = "storage_object_version", length = 128)
    private String storageObjectVersion;

    @Column(name = "expected_sha256", length = 64, nullable = false)
    private String expectedSha256;

    @Column(name = "verified_sha256", length = 64)
    private String verifiedSha256;

    @Column(name = "quota_reservation_bytes", nullable = false)
    private long quotaReservationBytes;

    @Version
    @Column(name = "state_version", nullable = false)
    private long stateVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "stored_at")
    private Instant storedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "deletion_started_at")
    private Instant deletionStartedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Transfer() {}

    public static Transfer create(
            String senderId,
            String originalFilename,
            long fileSizeBytes,
            String contentTypeHint,
            PrivacyMode privacyMode,
            String expectedSha256
    ) {
        Transfer t = new Transfer();
        t.id = UlidGenerator.generate();
        t.senderId = senderId;
        t.originalFilename = sanitizeFilename(originalFilename);
        t.fileSizeBytes = fileSizeBytes;
        t.contentTypeHint = contentTypeHint;
        t.privacyMode = privacyMode != null ? privacyMode : PrivacyMode.STANDARD;
        t.expectedSha256 = expectedSha256.toLowerCase();
        t.state = TransferState.CREATED;
        t.quotaReservationBytes = fileSizeBytes;
        t.createdAt = Instant.now();
        // Object key is opaque and isolated per transfer
        t.storageObjectKey = "transfers/" + t.id + "/content.bin";
        return t;
    }

    // ----------------------------------------------------------------
    // Domain State Transitions
    // ----------------------------------------------------------------

    public void markUploading() {
        assertState(TransferState.CREATED, TransferState.QUEUED);
        this.state = TransferState.UPLOADING;
    }

    public void markVerifying() {
        assertState(TransferState.UPLOADING);
        this.state = TransferState.VERIFYING;
    }

    public void markStoredAndVerified(String verifiedHash, Instant verifiedAt, Instant expiry) {
        assertState(TransferState.VERIFYING);
        this.verifiedSha256 = verifiedHash.toLowerCase();
        this.storedAt = verifiedAt;
        this.expiresAt = expiry;
        this.state = TransferState.STORED_AND_VERIFIED;
    }

    public void makeAvailableToRecipient() {
        assertState(TransferState.STORED_AND_VERIFIED);
        this.state = TransferState.AVAILABLE_TO_RECIPIENT;
    }

    public void cancel() {
        if (this.state.isTerminal()) {
            throw new IllegalStateException("Cannot cancel transfer in terminal state: " + this.state);
        }
        this.state = TransferState.CANCELLED;
    }

    public void markExpired(Instant now) {
        if (this.state == TransferState.STORED_AND_VERIFIED || this.state == TransferState.AVAILABLE_TO_RECIPIENT) {
            this.state = TransferState.EXPIRED;
        }
    }

    public void markDeletionPending(Instant now) {
        this.deletionStartedAt = now;
        this.state = TransferState.DELETION_PENDING;
    }

    public void markDeleted(Instant now) {
        this.deletedAt = now;
        this.state = TransferState.DELETED;
    }

    public void markFailed() {
        this.state = TransferState.FAILED;
    }

    private void assertState(TransferState... expected) {
        for (TransferState exp : expected) {
            if (this.state == exp) return;
        }
        throw new IllegalStateException("Illegal transfer state transition from " + this.state);
    }

    private static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) return "unnamed_file";
        // Strip path traversal attempts and control characters
        String clean = filename.replace("\\", "/");
        int lastSlash = clean.lastIndexOf('/');
        if (lastSlash >= 0) {
            clean = clean.substring(lastSlash + 1);
        }
        clean = clean.replaceAll("[\\r\\n\\t\\u0000]", "").trim();
        return clean.isEmpty() ? "unnamed_file" : (clean.length() > 255 ? clean.substring(0, 255) : clean);
    }

    // ----------------------------------------------------------------
    // Getters
    // ----------------------------------------------------------------

    public String getId() { return id; }
    public String getSenderId() { return senderId; }
    public String getOriginalFilename() { return originalFilename; }
    public long getFileSizeBytes() { return fileSizeBytes; }
    public String getContentTypeHint() { return contentTypeHint; }
    public PrivacyMode getPrivacyMode() { return privacyMode; }
    public TransferState getState() { return state; }
    public String getStorageObjectKey() { return storageObjectKey; }
    public String getStorageObjectVersion() { return storageObjectVersion; }
    public String getExpectedSha256() { return expectedSha256; }
    public String getVerifiedSha256() { return verifiedSha256; }
    public long getQuotaReservationBytes() { return quotaReservationBytes; }
    public long getStateVersion() { return stateVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStoredAt() { return storedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getDeletionStartedAt() { return deletionStartedAt; }
    public Instant getDeletedAt() { return deletedAt; }
}
