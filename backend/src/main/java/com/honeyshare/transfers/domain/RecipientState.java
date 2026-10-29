package com.honeyshare.transfers.domain;

/**
 * Recipient states defined in HoneyShare Specification Section 8.2.
 */
public enum RecipientState {
    INVITATION_REQUIRED,
    AWAITING_EMAIL_VERIFICATION,
    AVAILABLE,
    DOWNLOAD_STARTED,
    DOWNLOADING,
    VERIFIED_AT_RECEIVER,
    CANCELLED,
    EXPIRED,
    FAILED
}

