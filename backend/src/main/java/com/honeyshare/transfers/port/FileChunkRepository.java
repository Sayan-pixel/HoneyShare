package com.honeyshare.transfers.port;

import com.honeyshare.transfers.domain.ChunkState;
import com.honeyshare.transfers.domain.FileChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FileChunkRepository extends JpaRepository<FileChunk, String> {

    List<FileChunk> findByUploadSessionIdOrderByChunkIndexAsc(String uploadSessionId);

    Optional<FileChunk> findByUploadSessionIdAndChunkIndex(String uploadSessionId, int chunkIndex);

    long countByUploadSessionIdAndState(String uploadSessionId, ChunkState state);
}

