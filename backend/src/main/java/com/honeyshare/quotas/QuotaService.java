package com.honeyshare.quotas;

import com.honeyshare.common.exception.QuotaExceededException;
import com.honeyshare.config.TransferProperties;
import com.honeyshare.transfers.domain.QuotaReservation;
import com.honeyshare.transfers.domain.QuotaReservationState;
import com.honeyshare.transfers.port.QuotaReservationRepository;
import com.honeyshare.transfers.port.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private final QuotaReservationRepository quotaReservationRepository;
    private final TransferRepository transferRepository;
    private final TransferProperties transferProperties;

    public QuotaService(
            QuotaReservationRepository quotaReservationRepository,
            TransferRepository transferRepository,
            TransferProperties transferProperties
    ) {
        this.quotaReservationRepository = quotaReservationRepository;
        this.transferRepository = transferRepository;
        this.transferProperties = transferProperties;
    }

    /**
     * Atomically check capacity and create an active quota reservation.
     */
    @Transactional
    public QuotaReservation reserveQuota(String userId, String transferId, long requestedBytes) {
        long maxFileSize = transferProperties.getUpload().getMaxFileSizeBytes();
        if (requestedBytes > maxFileSize) {
            throw new QuotaExceededException("File size " + requestedBytes + " bytes exceeds maximum individual limit of " + maxFileSize + " bytes");
        }

        long userLimit = transferProperties.getQuota().getDefaultUserBytes();
        long activeReservations = quotaReservationRepository.sumActiveReservationsByUserId(userId);
        long storedBytes = transferRepository.sumStoredBytesBySenderId(userId);

        if (activeReservations + storedBytes + requestedBytes > userLimit) {
            long available = Math.max(0, userLimit - (activeReservations + storedBytes));
            throw new QuotaExceededException("Quota exceeded. Requested: " + requestedBytes + " bytes, available: " + available + " bytes (limit: " + userLimit + " bytes)");
        }

        QuotaReservation reservation = QuotaReservation.create(userId, transferId, requestedBytes);
        quotaReservationRepository.save(reservation);

        log.info("Quota reserved: userId={} transferId={} bytes={}", userId, transferId, requestedBytes);
        return reservation;
    }

    @Transactional
    public void commitReservation(String transferId) {
        quotaReservationRepository.findByTransferIdAndState(transferId, QuotaReservationState.ACTIVE)
                .ifPresent(qr -> {
                    qr.markCommitted();
                    quotaReservationRepository.save(qr);
                    log.info("Quota reservation committed: transferId={} bytes={}", transferId, qr.getReservedBytes());
                });
    }

    @Transactional
    public void releaseReservation(String transferId) {
        quotaReservationRepository.findByTransferIdAndState(transferId, QuotaReservationState.ACTIVE)
                .ifPresent(qr -> {
                    qr.markReleased(Instant.now());
                    quotaReservationRepository.save(qr);
                    log.info("Quota reservation released: transferId={} bytes={}", transferId, qr.getReservedBytes());
                });
    }

    @Transactional
    public void releaseCommittedReservation(String transferId) {
        quotaReservationRepository.findByTransferIdAndState(transferId, QuotaReservationState.COMMITTED)
                .ifPresent(qr -> {
                    qr.markReleased(Instant.now());
                    quotaReservationRepository.save(qr);
                    log.info("Committed quota reservation released on deletion: transferId={} bytes={}", transferId, qr.getReservedBytes());
                });
    }

    @Transactional(readOnly = true)
    public QuotaStatus getQuotaStatus(String userId) {
        long userLimit = transferProperties.getQuota().getDefaultUserBytes();
        long activeReservations = quotaReservationRepository.sumActiveReservationsByUserId(userId);
        long storedBytes = transferRepository.sumStoredBytesBySenderId(userId);
        long totalUsed = activeReservations + storedBytes;
        long remaining = Math.max(0, userLimit - totalUsed);

        return new QuotaStatus(userLimit, storedBytes, activeReservations, remaining);
    }

    public record QuotaStatus(long limitBytes, long storedBytes, long reservedBytes, long remainingBytes) {}
}

