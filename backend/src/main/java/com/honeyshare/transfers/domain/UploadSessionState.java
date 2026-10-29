package com.honeyshare.transfers.domain;

/**
 * Upload session states defined in HoneyShare Specification Section 8.3.
 */
public enum UploadSessionState {
    INITIATED,
    ACTIVE,
    PAUSED,
    COMPLETING,
    VERIFYING,
    COMPLETED,
    ABORT_PENDING,
    ABORTED,
    FAILED
}

