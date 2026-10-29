package com.honeyshare.transfers.port;

import com.honeyshare.transfers.domain.UploadSession;
import com.honeyshare.transfers.domain.UploadSessionState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface UploadSessionRepository extends JpaRepository<UploadSession, String> {

    List<UploadSession> findByTransferId(String transferId);

    Optional<UploadSession> findFirstByTransferIdAndStateOrderByCreatedAtDesc(
            String transferId, UploadSessionState state);

    @Query("SELECT us FROM UploadSession us WHERE us.lastActivityAt <= :cutoff AND us.state IN ('INITIATED', 'ACTIVE', 'PAUSED')")
    List<UploadSession> findStaleSessions(Instant cutoff);
}

