package com.honeyshare.transfers.domain;

import com.honeyshare.common.UlidGenerator;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Tracks status, byte length, and verification of an individual multipart chunk.
 */
@Entity
@Table(name = "file_chunks")
public class FileChunk {

    @Id
    @Column(length = 26, nullable = false, updatable = false)
    private String id;

    @Column(name = "upload_session_id", length = 26, nullable = false)
    private String uploadSessionId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "byte_length", nullable = false)
    private long byteLength;

    @Column(name = "checksum_algorithm", length = 32, nullable = false)
    private String checksumAlgorithm;

    @Column(name = "checksum_value", length = 128)
    private String checksumValue;

    @Column(name = "storage_part_id", length = 256)
    private String storagePartId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private ChunkState state;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    protected FileChunk() {}

    public static FileChunk create(String uploadSessionId, int chunkIndex, long byteLength) {
        FileChunk c = new FileChunk();
        c.id = UlidGenerator.generate();
        c.uploadSessionId = uploadSessionId;
        c.chunkIndex = chunkIndex;
        c.byteLength = byteLength;
        c.checksumAlgorithm = "SHA-256";
        c.state = ChunkState.PENDING;
        c.createdAt = Instant.now();
        return c;
    }

    public void markUploading() {
        this.state = ChunkState.UPLOADING;
    }

    public void markCommitted(String partEtag, String checksumValue, Instant now) {
        this.storagePartId = partEtag;
        this.checksumValue = checksumValue;
        this.state = ChunkState.COMMITTED;
        this.verifiedAt = now;
    }

    public void markVerified(Instant now) {
        this.state = ChunkState.VERIFIED;
        this.verifiedAt = now;
    }

    public void markFailed() {
        this.state = ChunkState.FAILED;
    }

    public String getId() { return id; }
    public String getUploadSessionId() { return uploadSessionId; }
    public int getChunkIndex() { return chunkIndex; }
    public long getByteLength() { return byteLength; }
    public String getChecksumAlgorithm() { return checksumAlgorithm; }
    public String getChecksumValue() { return checksumValue; }
    public String getStoragePartId() { return storagePartId; }
    public ChunkState getState() { return state; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getVerifiedAt() { return verifiedAt; }
}

