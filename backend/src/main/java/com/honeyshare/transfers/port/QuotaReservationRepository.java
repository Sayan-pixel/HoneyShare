package com.honeyshare.transfers.port;

import com.honeyshare.transfers.domain.QuotaReservation;
import com.honeyshare.transfers.domain.QuotaReservationState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface QuotaReservationRepository extends JpaRepository<QuotaReservation, String> {

    @Query("SELECT COALESCE(SUM(qr.reservedBytes), 0) FROM QuotaReservation qr WHERE qr.userId = :userId AND qr.state = 'ACTIVE'")
    long sumActiveReservationsByUserId(String userId);

    Optional<QuotaReservation> findByTransferIdAndState(String transferId, QuotaReservationState state);
}

