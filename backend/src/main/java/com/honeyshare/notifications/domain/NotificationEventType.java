package com.honeyshare.notifications.domain;

/**
 * Standard notification event types defined in Specification Section 12.
 */
public enum NotificationEventType {
    INVITATION_CREATED,
    EMAIL_VERIFICATION_REQUIRED,
    TRANSFER_STORED,
    TRANSFER_RECEIVED,
    TRANSFER_EXPIRING,
    TRANSFER_EXPIRED,
    TRANSFER_CANCELLED,
    DOWNLOAD_COMPLETED_REPORTED,
    SECURITY_ALERT
}

