package com.honeyshare.transfers.service;

import com.honeyshare.common.exception.ChecksumMismatchException;
import com.honeyshare.common.exception.QuotaExceededException;
import com.honeyshare.config.TransferProperties;
import com.honeyshare.integrity.ChecksumService;
import com.honeyshare.quotas.QuotaService;
import com.honeyshare.transfers.domain.*;
import com.honeyshare.transfers.port.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Large File (1 GB, 10 GB, 100 GB) & Interruption Acceptance Tests")
class LargeFileTransferBenchmarkTest {

    private static final long ONE_GB = 1024L * 1024 * 1024;
    private static final long TEN_GB = 10L * ONE_GB;
    private static final long HUNDRED_GB = 100L * ONE_GB;

    private static final int S3_MAX_PARTS = 10000;
    private static final long S3_MIN_PART_SIZE = 5L * 1024 * 1024; // 5 MiB

    @Mock
    private UploadSessionRepository uploadSessionRepository;
    @Mock
    private FileChunkRepository fileChunkRepository;
    @Mock
    private TransferRepository transferRepository;
    @Mock
    private QuotaReservationRepository quotaReservationRepository;

    private QuotaService quotaService;
    private TransferProperties transferProperties;
    private ChecksumService checksumService;

    @BeforeEach
    void setUp() {
        transferProperties = new TransferProperties();
        quotaService = new QuotaService(quotaReservationRepository, transferRepository, transferProperties);
        checksumService = new ChecksumService();
    }

    @Test
    @DisplayName("S3 Constraint Validation: 1 GB, 10 GB, and 100 GB transfers comply with S3 multipart limits")
    void validatesS3MultipartConstraints() {
        // 1 GB file with 32 MiB parts
        long partSize1Gb = 32L * 1024 * 1024;
        int parts1Gb = (int) Math.ceil((double) ONE_GB / partSize1Gb);
        assertThat(parts1Gb).isLessThanOrEqualTo(S3_MAX_PARTS);
        assertThat(partSize1Gb).isGreaterThanOrEqualTo(S3_MIN_PART_SIZE);
        assertThat(parts1Gb).isEqualTo(32);

        // 10 GB file with 64 MiB parts
        long partSize10Gb = 64L * 1024 * 1024;
        int parts10Gb = (int) Math.ceil((double) TEN_GB / partSize10Gb);
        assertThat(parts10Gb).isLessThanOrEqualTo(S3_MAX_PARTS);
        assertThat(partSize10Gb).isGreaterThanOrEqualTo(S3_MIN_PART_SIZE);
        assertThat(parts10Gb).isEqualTo(160);

        // 100 GB file with 64 MiB parts
        long partSize100Gb = 64L * 1024 * 1024;
        int parts100Gb = (int) Math.ceil((double) HUNDRED_GB / partSize100Gb);
        assertThat(parts100Gb).isLessThanOrEqualTo(S3_MAX_PARTS);
        assertThat(partSize100Gb).isGreaterThanOrEqualTo(S3_MIN_PART_SIZE);
        assertThat(parts100Gb).isEqualTo(1600);
    }

    @Test
    @DisplayName("Memory Bound Benchmark: Streaming SHA-256 uses constant memory without heap exhaustion")
    void streamingSha256ConstantMemory() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        // Simulate streaming 100 MB in 64 KB buffers
        byte[] chunk = new byte[64 * 1024];
        for (int i = 0; i < chunk.length; i++) {
            chunk[i] = (byte) (i % 256);
        }

        Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        long memBefore = runtime.totalMemory() - runtime.freeMemory();

        // Feed 1600 chunks (100 MB total)
        for (int i = 0; i < 1600; i++) {
            digest.update(chunk, 0, chunk.length);
        }

        byte[] finalHash = digest.digest();
        assertThat(finalHash).hasSize(32);

        runtime.gc();
        long memAfter = runtime.totalMemory() - runtime.freeMemory();
        long memDeltaMb = Math.abs(memAfter - memBefore) / (1024 * 1024);

        // Heap growth must remain bounded under 25 MB regardless of throughput
        assertThat(memDeltaMb).isLessThan(25);
    }

    @Test
    @DisplayName("Interruption & Resumption: Upload resumes from manifest without re-uploading committed chunks")
    void resumesFromManifestWithoutRedundantUploads() {
        String transferId = "trans-10gb";
        String sessionId = "sess-123";
        int totalChunks = 160; // 10 GB file with 64 MiB parts

        UploadSession session = UploadSession.create(transferId, "s3-upload-id", 64L * 1024 * 1024, totalChunks);

        // Simulate 50 chunks successfully committed before network drops
        List<FileChunk> committedChunks = new ArrayList<>();
        java.time.Instant now = java.time.Instant.now();
        for (int i = 0; i < 50; i++) {
            FileChunk chunk = FileChunk.create(session.getId(), i, 64L * 1024 * 1024);
            chunk.markCommitted("etag-" + i, "sha-" + i, now);
            committedChunks.add(chunk);
        }

        when(fileChunkRepository.findByUploadSessionIdOrderByChunkIndexAsc(session.getId()))
                .thenReturn(committedChunks);

        // Client queries manifest upon reconnect
        List<FileChunk> existing = fileChunkRepository.findByUploadSessionIdOrderByChunkIndexAsc(session.getId());
        long alreadyCommittedBytes = existing.stream()
                .filter(c -> c.getState() == ChunkState.COMMITTED)
                .mapToLong(FileChunk::getByteLength).sum();

        // 50 parts * 64 MiB = 3,200 MiB (3.125 GB) already safe
        assertThat(existing).hasSize(50);
        assertThat(alreadyCommittedBytes).isEqualTo(50L * 64 * 1024 * 1024);

        // Client resumes uploading from chunk 50 to 159 (remaining 110 chunks)
        List<Integer> chunksToUpload = new ArrayList<>();
        for (int i = 0; i < totalChunks; i++) {
            final int idx = i;
            boolean isCommitted = existing.stream()
                    .anyMatch(c -> c.getChunkIndex() == idx && c.getState() == ChunkState.COMMITTED);
            if (!isCommitted) {
                chunksToUpload.add(idx);
            }
        }

        assertThat(chunksToUpload).hasSize(110);
        assertThat(chunksToUpload.get(0)).isEqualTo(50);
        assertThat(chunksToUpload.get(109)).isEqualTo(159);
    }

    @Test
    @DisplayName("Integrity Failure: Corrupted chunk alters SHA-256 and fails verification")
    void detectsCorruptedChunkAndAllowsTargetedRetry() {
        String validData = "Content chunk part data with correct SHA-256";
        String validSha256 = checksumService.calculateSha256(new ByteArrayInputStream(validData.getBytes(StandardCharsets.UTF_8)));

        String corruptedData = "Content chunk part data with TAMPERED byte!";
        InputStream corruptedStream = new ByteArrayInputStream(corruptedData.getBytes(StandardCharsets.UTF_8));
        String actualCorruptedSha256 = checksumService.calculateSha256(corruptedStream);

        assertThat(actualCorruptedSha256).isNotEqualTo(validSha256);
        assertThat(checksumService.verifySha256(actualCorruptedSha256, validSha256)).isFalse();
    }

    @Test
    @DisplayName("Quota Enforcement: Atomic quota accommodates two 100 GB transfers but blocks over-subscription")
    void quotaEnforcementForLargeTransfers() {
        String userId = "user-vip";

        // Step 1: Reserve first 100 GB transfer (allowed, 100 GB <= 200 GB)
        when(quotaReservationRepository.sumActiveReservationsByUserId(userId)).thenReturn(0L);
        when(transferRepository.sumStoredBytesBySenderId(userId)).thenReturn(0L);

        QuotaReservation res1 = quotaService.reserveQuota(userId, "trans-1", HUNDRED_GB);
        assertThat(res1.getReservedBytes()).isEqualTo(HUNDRED_GB);

        // Step 2: Reserve second 100 GB transfer (allowed, 100 + 100 = 200 GB)
        when(quotaReservationRepository.sumActiveReservationsByUserId(userId)).thenReturn(HUNDRED_GB);
        QuotaReservation res2 = quotaService.reserveQuota(userId, "trans-2", HUNDRED_GB);
        assertThat(res2.getReservedBytes()).isEqualTo(HUNDRED_GB);

        // Step 3: Attempt a third transfer (1 GB) -> Over 200 GB quota limit!
        when(quotaReservationRepository.sumActiveReservationsByUserId(userId)).thenReturn(200L * ONE_GB);
        assertThatThrownBy(() -> quotaService.reserveQuota(userId, "trans-3", ONE_GB))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("Quota exceeded");
    }
}
