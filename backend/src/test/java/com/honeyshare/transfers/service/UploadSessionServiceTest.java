package com.honeyshare.transfers.service;

import com.honeyshare.audit.AuditService;
import com.honeyshare.common.exception.ChecksumMismatchException;
import com.honeyshare.config.TransferProperties;
import com.honeyshare.integrity.ChecksumService;
import com.honeyshare.quotas.QuotaService;
import com.honeyshare.storage.StoragePort;
import com.honeyshare.storage.StorageProperties;
import com.honeyshare.transfers.domain.*;
import com.honeyshare.transfers.port.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UploadSessionServiceTest {

    @Mock TransferRepository transferRepository;
    @Mock UploadSessionRepository sessionRepository;
    @Mock FileChunkRepository chunkRepository;
    @Mock TransferRecipientRepository recipientRepository;
    @Mock StoragePort storagePort;
    @Mock AuditService auditService;
    @Mock QuotaService quotaService;
    @Mock com.honeyshare.notifications.service.OutboxService outboxService;

    StorageProperties storageProperties;
    TransferProperties transferProperties;
    ChecksumService checksumService;
    UploadSessionService sessionService;

    @BeforeEach
    void setUp() {
        storageProperties = new StorageProperties();
        transferProperties = new TransferProperties();
        // 10 MB chunks
        transferProperties.getUpload().setChunkSizeBytes(10 * 1024 * 1024L);
        checksumService = new ChecksumService();

        sessionService = new UploadSessionService(
                transferRepository,
                sessionRepository,
                chunkRepository,
                recipientRepository,
                storagePort,
                storageProperties,
                transferProperties,
                checksumService,
                quotaService,
                auditService,
                outboxService
        );
    }

    @Test
    void createUploadSession_initiatesMultipartAndCreatesChunks() {
        // 25 MB file -> 3 chunks (10 MB, 10 MB, 5 MB)
        long fileSize = 25 * 1024 * 1024L;
        Transfer t = Transfer.create("sender-1", "large.iso", fileSize, "application/octet-stream", PrivacyMode.STANDARD, "expectedhash");

        when(transferRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(storagePort.initiateMultipartUpload(anyString(), any())).thenReturn("upload-id-123");
        when(sessionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        UploadSession session = sessionService.createUploadSession(t.getId(), "sender-1", "127.0.0.1");

        assertThat(session).isNotNull();
        assertThat(session.getStorageUploadId()).isEqualTo("upload-id-123");
        assertThat(session.getExpectedChunkCount()).isEqualTo(3);
        assertThat(t.getState()).isEqualTo(TransferState.UPLOADING);

        verify(chunkRepository, times(3)).save(any(FileChunk.class));
        verify(storagePort).initiateMultipartUpload(eq(t.getStorageObjectKey()), eq("application/octet-stream"));
    }

    @Test
    void authorizePart_generatesPresignedUrl() {
        Transfer t = Transfer.create("sender-1", "file.dat", 1000, null, PrivacyMode.STANDARD, "hash");
        UploadSession s = UploadSession.create(t.getId(), "upload-id-123", 1000, 1);
        FileChunk chunk = FileChunk.create(s.getId(), 1, 1000);

        when(sessionRepository.findById(s.getId())).thenReturn(Optional.of(s));
        when(transferRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(chunkRepository.findByUploadSessionIdAndChunkIndex(s.getId(), 1)).thenReturn(Optional.of(chunk));
        when(storagePort.generatePresignedUploadPartUrl(anyString(), anyString(), eq(1), any(Duration.class)))
                .thenReturn("https://storage.local/upload-part-1");

        var auth = sessionService.authorizePart(s.getId(), 1, "sender-1");

        assertThat(auth.presignedUploadUrl()).isEqualTo("https://storage.local/upload-part-1");
        assertThat(chunk.getState()).isEqualTo(ChunkState.UPLOADING);
    }

    @Test
    void commitPart_idempotentlyRecordsChunk() {
        Transfer t = Transfer.create("sender-1", "file.dat", 1000, null, PrivacyMode.STANDARD, "hash");
        UploadSession s = UploadSession.create(t.getId(), "upload-id-123", 1000, 1);
        FileChunk chunk = FileChunk.create(s.getId(), 1, 1000);

        when(sessionRepository.findById(s.getId())).thenReturn(Optional.of(s));
        when(transferRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(chunkRepository.findByUploadSessionIdAndChunkIndex(s.getId(), 1)).thenReturn(Optional.of(chunk));
        when(chunkRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        FileChunk committed = sessionService.commitPart(s.getId(), 1, "etag-chunk-1", "sha256chunk", "sender-1");
        assertThat(committed.getState()).isEqualTo(ChunkState.COMMITTED);
        assertThat(committed.getStoragePartId()).isEqualTo("etag-chunk-1");

        // Repeated commit returns without modifying state
        FileChunk repeated = sessionService.commitPart(s.getId(), 1, "etag-chunk-1", "sha256chunk", "sender-1");
        assertThat(repeated.getState()).isEqualTo(ChunkState.COMMITTED);
    }

    @Test
    void completeUploadSession_matchingChecksum_issuesDurableReceipt() {
        String testData = "HoneyShare dependable file payload content";
        byte[] bytes = testData.getBytes(StandardCharsets.UTF_8);
        String expectedHash = checksumService.calculateSha256(new ByteArrayInputStream(bytes));

        Transfer t = Transfer.create("sender-1", "data.txt", bytes.length, null, PrivacyMode.STANDARD, expectedHash);
        t.markUploading();

        UploadSession s = UploadSession.create(t.getId(), "upload-id-123", bytes.length, 1);
        FileChunk chunk = FileChunk.create(s.getId(), 1, bytes.length);
        chunk.markCommitted("etag-part-1", expectedHash, Instant.now());

        when(sessionRepository.findById(s.getId())).thenReturn(Optional.of(s));
        when(transferRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(chunkRepository.findByUploadSessionIdOrderByChunkIndexAsc(s.getId())).thenReturn(List.of(chunk));
        when(storagePort.getObjectSize(t.getStorageObjectKey())).thenReturn((long) bytes.length);
        when(storagePort.openObjectStream(t.getStorageObjectKey())).thenAnswer(i -> new ByteArrayInputStream(bytes));

        var receipt = sessionService.completeUploadSession(s.getId(), "sender-1", "127.0.0.1");

        assertThat(receipt).isNotNull();
        assertThat(receipt.transferId()).isEqualTo(t.getId());
        assertThat(receipt.verifiedSha256()).isEqualTo(expectedHash);
        assertThat(t.getState()).isEqualTo(TransferState.AVAILABLE_TO_RECIPIENT);
        assertThat(s.getState()).isEqualTo(UploadSessionState.COMPLETED);

        verify(quotaService).commitReservation(t.getId());
        verify(storagePort).completeMultipartUpload(eq(t.getStorageObjectKey()), eq("upload-id-123"), anyList());
    }

    @Test
    void completeUploadSession_checksumMismatch_marksFailedAndThrows() {
        byte[] bytes = "Corrupted data returned from storage".getBytes(StandardCharsets.UTF_8);
        String expectedHash = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"; // "hello world"

        Transfer t = Transfer.create("sender-1", "data.txt", bytes.length, null, PrivacyMode.STANDARD, expectedHash);
        t.markUploading();

        UploadSession s = UploadSession.create(t.getId(), "upload-id-123", bytes.length, 1);
        FileChunk chunk = FileChunk.create(s.getId(), 1, bytes.length);
        chunk.markCommitted("etag-part-1", "somehash", Instant.now());

        when(sessionRepository.findById(s.getId())).thenReturn(Optional.of(s));
        when(transferRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(chunkRepository.findByUploadSessionIdOrderByChunkIndexAsc(s.getId())).thenReturn(List.of(chunk));
        when(storagePort.getObjectSize(t.getStorageObjectKey())).thenReturn((long) bytes.length);
        when(storagePort.openObjectStream(t.getStorageObjectKey())).thenAnswer(i -> new ByteArrayInputStream(bytes));

        assertThatThrownBy(() -> sessionService.completeUploadSession(s.getId(), "sender-1", "127.0.0.1"))
                .isInstanceOf(ChecksumMismatchException.class);

        // Crucial requirement: NEVER mark transfer as success when integrity fails!
        assertThat(t.getState()).isEqualTo(TransferState.FAILED);
        assertThat(s.getState()).isEqualTo(UploadSessionState.FAILED);
        verify(quotaService, never()).commitReservation(any());
    }
}

