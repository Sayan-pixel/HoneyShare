package com.honeyshare.downloads.domain;

/**
 * Lifecycle states of an authorized recipient download session.
 * Complies with HoneyShare Specification Section 8.4.
 */
public enum DownloadSessionState {
    AUTHORIZED,
    IN_PROGRESS,
    COMPLETED_REPORTED,
    FAILED,
    ABANDONED,
    EXPIRED;

    public boolean isActive() {
        return this == AUTHORIZED || this == IN_PROGRESS;
    }

    public boolean isTerminal() {
        return this == COMPLETED_REPORTED || this == FAILED || this == ABANDONED || this == EXPIRED;
    }
}

