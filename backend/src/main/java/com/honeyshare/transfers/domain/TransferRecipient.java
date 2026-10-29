package com.honeyshare.transfers.domain;

import com.honeyshare.common.UlidGenerator;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Tracks delivery status per designated recipient for a given transfer.
 */
@Entity
@Table(name = "transfer_recipients")
public class TransferRecipient {

    @Id
    @Column(length = 26, nullable = false, updatable = false)
    private String id;

    @Column(name = "transfer_id", length = 26, nullable = false)
    private String transferId;

    @Column(name = "recipient_user_id", length = 26, nullable = false)
    private String recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 35)
    private RecipientState state;

    @Column(name = "invitation_id", length = 26)
    private String invitationId;

    @Column(name = "available_at")
    private Instant availableAt;

    @Column(name = "download_started_at")
    private Instant downloadStartedAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TransferRecipient() {}

    public static TransferRecipient create(String transferId, String recipientUserId, boolean emailVerified) {
        TransferRecipient tr = new TransferRecipient();
        tr.id = UlidGenerator.generate();
        tr.transferId = transferId;
        tr.recipientUserId = recipientUserId;
        tr.state = emailVerified ? RecipientState.AVAILABLE : RecipientState.AWAITING_EMAIL_VERIFICATION;
        tr.createdAt = Instant.now();
        return tr;
    }

    public void markAvailable(Instant now) {
        this.availableAt = now;
        this.state = RecipientState.AVAILABLE;
    }

    public void markDownloadStarted(Instant now) {
        this.downloadStartedAt = now;
        this.state = RecipientState.DOWNLOAD_STARTED;
    }

    public void markVerifiedAtReceiver(Instant now) {
        this.verifiedAt = now;
        this.state = RecipientState.VERIFIED_AT_RECEIVER;
    }

    public void markCancelled() {
        this.state = RecipientState.CANCELLED;
    }

    public void markExpired() {
        this.state = RecipientState.EXPIRED;
    }

    public String getId() { return id; }
    public String getTransferId() { return transferId; }
    public String getRecipientUserId() { return recipientUserId; }
    public RecipientState getState() { return state; }
    public Instant getAvailableAt() { return availableAt; }
    public Instant getDownloadStartedAt() { return downloadStartedAt; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public Instant getCreatedAt() { return createdAt; }
}

