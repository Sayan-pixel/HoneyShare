package com.honeyshare.downloads.port;

import com.honeyshare.downloads.domain.DownloadSession;
import com.honeyshare.downloads.domain.DownloadSessionState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface DownloadSessionRepository extends JpaRepository<DownloadSession, String> {

    List<DownloadSession> findByTransferId(String transferId);

    List<DownloadSession> findByRecipientUserId(String recipientUserId);

    Optional<DownloadSession> findFirstByTransferIdAndRecipientUserIdAndStateInOrderByCreatedAtDesc(
            String transferId, String recipientUserId, List<DownloadSessionState> states);

    @Query("SELECT ds FROM DownloadSession ds WHERE ds.expiresAt <= :now AND ds.state IN ('AUTHORIZED', 'IN_PROGRESS')")
    List<DownloadSession> findActiveExpiredSessions(Instant now);

    @Query("SELECT COUNT(ds) > 0 FROM DownloadSession ds WHERE ds.transferId = :transferId AND ds.state IN ('AUTHORIZED', 'IN_PROGRESS') AND ds.expiresAt > :now")
    boolean hasActiveUnexpiredSessions(String transferId, Instant now);
}

