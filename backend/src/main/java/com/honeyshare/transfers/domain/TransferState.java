package com.honeyshare.transfers.domain;

/**
 * Transfer states defined in HoneyShare Specification Section 8.1.
 *
 * Transitions are strictly enforced and tracked with state version numbers.
 */
public enum TransferState {
    CREATED,
    QUEUED,
    UPLOADING,
    VERIFYING,
    STORED_AND_VERIFIED,
    AVAILABLE_TO_RECIPIENT,
    PARTIALLY_DELIVERED,
    DELIVERED,
    CANCELLED,
    EXPIRED,
    DELETION_PENDING,
    DELETED,
    FAILED;

    public boolean isTerminal() {
        return this == CANCELLED || this == EXPIRED || this == DELETED || this == FAILED;
    }

    public boolean canDownload() {
        return this == STORED_AND_VERIFIED || this == AVAILABLE_TO_RECIPIENT || this == PARTIALLY_DELIVERED;
    }
}

