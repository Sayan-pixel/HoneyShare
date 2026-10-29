package com.honeyshare.downloads.domain;

import com.honeyshare.common.UlidGenerator;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Tracks an individual authorized download session for a transfer and recipient.
 * Enforces the fixed 24-hour active-download grace period.
 * Reconnects increment reconnect_count but NEVER reset the grace-period clock (expires_at).
 */
@Entity
@Table(name = "download_sessions")
public class DownloadSession {

    @Id
    @Column(length = 26, nullable = false, updatable = false)
    private String id;

    @Column(name = "transfer_id", length = 26, nullable = false)
    private String transferId;

    @Column(name = "recipient_user_id", length = 26, nullable = false)
    private String recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 30)
    private DownloadSessionState state;

    @Column(name = "authorized_at", nullable = false)
    private Instant authorizedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "reconnect_count", nullable = false)
    private int reconnectCount;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DownloadSession() {}

    public static DownloadSession create(
            String transferId,
            String recipientUserId,
            Instant expiresAt,
            String ipAddress
    ) {
        DownloadSession session = new DownloadSession();
        session.id = UlidGenerator.generate();
        session.transferId = transferId;
        session.recipientUserId = recipientUserId;
        session.state = DownloadSessionState.AUTHORIZED;
        session.authorizedAt = Instant.now();
        session.expiresAt = expiresAt;
        session.reconnectCount = 0;
        session.ipAddress = ipAddress;
        session.createdAt = Instant.now();
        return session;
    }

    public void markInProgress() {
        if (this.state == DownloadSessionState.AUTHORIZED) {
            this.state = DownloadSessionState.IN_PROGRESS;
        }
    }

    /**
     * Record a client reconnection/resume.
     * Note: Does NOT extend expiresAt. The grace-period clock remains fixed.
     */
    public void recordReconnect() {
        if (this.state.isTerminal()) {
            throw new IllegalStateException("Cannot reconnect to terminal download session: " + this.state);
        }
        this.reconnectCount++;
        this.state = DownloadSessionState.IN_PROGRESS;
    }

    public void markCompleted(Instant now) {
        this.completedAt = now;
        this.state = DownloadSessionState.COMPLETED_REPORTED;
    }

    public void markAbandoned() {
        this.state = DownloadSessionState.ABANDONED;
    }

    public void markExpired() {
        this.state = DownloadSessionState.EXPIRED;
    }

    public void markFailed() {
        this.state = DownloadSessionState.FAILED;
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(this.expiresAt);
    }

    public String getId() { return id; }
    public String getTransferId() { return transferId; }
    public String getRecipientUserId() { return recipientUserId; }
    public DownloadSessionState getState() { return state; }
    public Instant getAuthorizedAt() { return authorizedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCompletedAt() { return completedAt; }
    public int getReconnectCount() { return reconnectCount; }
    public String getIpAddress() { return ipAddress; }
    public Instant getCreatedAt() { return createdAt; }
}

