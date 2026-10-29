package com.honeyshare.transfers.port;

import com.honeyshare.transfers.domain.Transfer;
import com.honeyshare.transfers.domain.TransferState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransferRepository extends JpaRepository<Transfer, String> {

    List<Transfer> findBySenderIdOrderByCreatedAtDesc(String senderId);

    @Query("SELECT t FROM Transfer t WHERE t.expiresAt <= :now AND t.state IN ('STORED_AND_VERIFIED', 'AVAILABLE_TO_RECIPIENT', 'PARTIALLY_DELIVERED')")
    List<Transfer> findExpiredTransfers(Instant now);

    @Query("SELECT t FROM Transfer t WHERE t.state = 'CANCELLED' OR (t.state = 'EXPIRED' AND t.expiresAt <= :graceDeadline)")
    List<Transfer> findTransfersEligibleForDeletion(Instant graceDeadline);

    List<Transfer> findByState(TransferState state);

    @Query("SELECT COALESCE(SUM(t.fileSizeBytes), 0) FROM Transfer t WHERE t.senderId = :senderId AND t.state IN ('STORED_AND_VERIFIED', 'AVAILABLE_TO_RECIPIENT', 'PARTIALLY_DELIVERED', 'DELIVERED')")
    long sumStoredBytesBySenderId(String senderId);
}

