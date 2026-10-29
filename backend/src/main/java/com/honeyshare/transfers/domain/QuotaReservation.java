package com.honeyshare.transfers.domain;

import com.honeyshare.common.UlidGenerator;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Tracks atomic quota reservations before large uploads begin.
 */
@Entity
@Table(name = "quota_reservations")
public class QuotaReservation {

    @Id
    @Column(length = 26, nullable = false, updatable = false)
    private String id;

    @Column(name = "user_id", length = 26, nullable = false)
    private String userId;

    @Column(name = "transfer_id", length = 26, nullable = false)
    private String transferId;

    @Column(name = "reserved_bytes", nullable = false)
    private long reservedBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private QuotaReservationState state;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    protected QuotaReservation() {}

    public static QuotaReservation create(String userId, String transferId, long bytes) {
        QuotaReservation qr = new QuotaReservation();
        qr.id = UlidGenerator.generate();
        qr.userId = userId;
        qr.transferId = transferId;
        qr.reservedBytes = bytes;
        qr.state = QuotaReservationState.ACTIVE;
        qr.createdAt = Instant.now();
        return qr;
    }

    public void markCommitted() {
        this.state = QuotaReservationState.COMMITTED;
    }

    public void markReleased(Instant now) {
        this.state = QuotaReservationState.RELEASED;
        this.releasedAt = now;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getTransferId() { return transferId; }
    public long getReservedBytes() { return reservedBytes; }
    public QuotaReservationState getState() { return state; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReleasedAt() { return releasedAt; }
}

