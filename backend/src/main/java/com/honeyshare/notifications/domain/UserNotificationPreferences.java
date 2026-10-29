package com.honeyshare.notifications.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Stores user preferences regarding email, push, and in-app notifications.
 */
@Entity
@Table(name = "user_notification_preferences")
public class UserNotificationPreferences {

    @Id
    @Column(name = "user_id", length = 26, nullable = false)
    private String userId;

    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled;

    @Column(name = "push_enabled", nullable = false)
    private boolean pushEnabled;

    @Column(name = "in_app_enabled", nullable = false)
    private boolean inAppEnabled;

    @Column(name = "notify_on_transfer_received", nullable = false)
    private boolean notifyOnTransferReceived;

    @Column(name = "notify_on_download_complete", nullable = false)
    private boolean notifyOnDownloadComplete;

    @Column(name = "notify_on_transfer_expiring", nullable = false)
    private boolean notifyOnTransferExpiring;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserNotificationPreferences() {}

    public static UserNotificationPreferences createDefault(String userId) {
        UserNotificationPreferences p = new UserNotificationPreferences();
        p.userId = userId;
        p.emailEnabled = true;
        p.pushEnabled = true;
        p.inAppEnabled = true;
        p.notifyOnTransferReceived = true;
        p.notifyOnDownloadComplete = true;
        p.notifyOnTransferExpiring = true;
        p.updatedAt = Instant.now();
        return p;
    }

    public void update(
            Boolean emailEnabled,
            Boolean pushEnabled,
            Boolean inAppEnabled,
            Boolean notifyOnTransferReceived,
            Boolean notifyOnDownloadComplete,
            Boolean notifyOnTransferExpiring
    ) {
        if (emailEnabled != null) this.emailEnabled = emailEnabled;
        if (pushEnabled != null) this.pushEnabled = pushEnabled;
        if (inAppEnabled != null) this.inAppEnabled = inAppEnabled;
        if (notifyOnTransferReceived != null) this.notifyOnTransferReceived = notifyOnTransferReceived;
        if (notifyOnDownloadComplete != null) this.notifyOnDownloadComplete = notifyOnDownloadComplete;
        if (notifyOnTransferExpiring != null) this.notifyOnTransferExpiring = notifyOnTransferExpiring;
        this.updatedAt = Instant.now();
    }

    public boolean allowsChannel(NotificationChannel channel) {
        return switch (channel) {
            case IN_APP -> inAppEnabled;
            case EMAIL -> emailEnabled;
            case PUSH -> pushEnabled;
        };
    }

    public String getUserId() { return userId; }
    public boolean isEmailEnabled() { return emailEnabled; }
    public boolean isPushEnabled() { return pushEnabled; }
    public boolean isInAppEnabled() { return inAppEnabled; }
    public boolean isNotifyOnTransferReceived() { return notifyOnTransferReceived; }
    public boolean isNotifyOnDownloadComplete() { return notifyOnDownloadComplete; }
    public boolean isNotifyOnTransferExpiring() { return notifyOnTransferExpiring; }
    public Instant getUpdatedAt() { return updatedAt; }
}

