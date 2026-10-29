-- V004: Download Sessions and Recipient Access
-- Complies with HoneyShare Specification Section 8.4, 9, and Table 6.

CREATE TABLE download_sessions (
    id                  CHAR(26)        NOT NULL,
    transfer_id         CHAR(26)        NOT NULL,
    recipient_user_id   CHAR(26)        NOT NULL,
    state               ENUM(
                            'AUTHORIZED',
                            'IN_PROGRESS',
                            'COMPLETED_REPORTED',
                            'FAILED',
                            'ABANDONED',
                            'EXPIRED'
                        ) NOT NULL DEFAULT 'AUTHORIZED',
    authorized_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at          DATETIME(6)     NOT NULL
                        COMMENT 'Fixed grace period deadline: transfer expiry + 24h grace. Reconnects do NOT reset this clock.',
    completed_at        DATETIME(6)     NULL,
    reconnect_count     INT             NOT NULL DEFAULT 0,
    ip_address          VARCHAR(45)     NULL,
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_download_sessions PRIMARY KEY (id),
    INDEX idx_dl_sessions_transfer (transfer_id),
    INDEX idx_dl_sessions_recipient (recipient_user_id),
    INDEX idx_dl_sessions_state (state),
    INDEX idx_dl_sessions_expires (expires_at),
    CONSTRAINT fk_dl_sessions_transfer FOREIGN KEY (transfer_id)
        REFERENCES transfers (id) ON DELETE CASCADE,
    CONSTRAINT fk_dl_sessions_recipient FOREIGN KEY (recipient_user_id)
        REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

