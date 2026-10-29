package com.honeyshare.notifications.domain;

import com.honeyshare.common.UlidGenerator;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Authoritative persisted in-app notification entry for a user.
 */
@Entity
@Table(name = "inbox_notifications")
public class InboxNotification {

    @Id
    @Column(length = 26, nullable = false, updatable = false)
    private String id;

    @Column(name = "user_id", length = 26, nullable = false)
    private String userId;

    @Column(name = "event_id", length = 128, nullable = false)
    private String eventId;

    @Column(name = "notification_type", length = 60, nullable = false)
    private String notificationType;

    @Column(name = "transfer_id", length = 26)
    private String transferId;

    @Column(name = "title", length = 255, nullable = false)
    private String title;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected InboxNotification() {}

    public static InboxNotification create(
            String userId,
            String eventId,
            String notificationType,
            String transferId,
            String title,
            String message
    ) {
        InboxNotification n = new InboxNotification();
        n.id = UlidGenerator.generate();
        n.userId = userId;
        n.eventId = eventId;
        n.notificationType = notificationType;
        n.transferId = transferId;
        n.title = title;
        n.message = message;
        n.createdAt = Instant.now();
        return n;
    }

    public void markRead(Instant now) {
        if (this.readAt == null) {
            this.readAt = now;
        }
    }

    public boolean isRead() {
        return this.readAt != null;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getEventId() { return eventId; }
    public String getNotificationType() { return notificationType; }
    public String getTransferId() { return transferId; }
    public String getTitle() { return title; }
    public String getMessage() { return message; }
    public Instant getReadAt() { return readAt; }
    public Instant getCreatedAt() { return createdAt; }
}

