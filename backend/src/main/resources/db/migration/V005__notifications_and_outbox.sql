-- V005: Durable Outbox, In-App Notifications, and User Notification Preferences
-- Complies with HoneyShare Specification Section 3.8, Section 5 (FR-NOTIFY-01 to FR-NOTIFY-06), Section 9.11, 9.12, and Section 12.

-- =====================================================================
-- outbox_events
-- Transactional outbox pattern: domain state and outbox events commit together in MySQL.
-- =====================================================================
CREATE TABLE outbox_events (
    id                  CHAR(26)        NOT NULL,
    user_id             CHAR(26)        NOT NULL,
    event_type          VARCHAR(60)     NOT NULL,
    event_id            VARCHAR(128)    NOT NULL
                        COMMENT 'Unique idempotent deduplication key preventing duplicate messages',
    channel             ENUM('IN_APP', 'EMAIL', 'PUSH') NOT NULL,
    payload             TEXT            NOT NULL
                        COMMENT 'Safe JSON payload; strictly no file contents, presigned URLs, or secrets',
    state               ENUM('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'DEAD_LETTER') NOT NULL DEFAULT 'PENDING',
    attempt_count       INT             NOT NULL DEFAULT 0,
    max_attempts        INT             NOT NULL DEFAULT 5,
    next_attempt_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    sent_at             DATETIME(6)     NULL,
    last_error          TEXT            NULL,
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_outbox_events PRIMARY KEY (id),
    CONSTRAINT uq_outbox_event_id UNIQUE (event_id, channel),
    INDEX idx_outbox_state_next (state, next_attempt_at),
    INDEX idx_outbox_user (user_id),
    CONSTRAINT fk_outbox_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- inbox_notifications
-- Authoritative persisted in-app notification inbox.
-- =====================================================================
CREATE TABLE inbox_notifications (
    id                  CHAR(26)        NOT NULL,
    user_id             CHAR(26)        NOT NULL,
    event_id            VARCHAR(128)    NOT NULL,
    notification_type   VARCHAR(60)     NOT NULL,
    transfer_id         CHAR(26)        NULL,
    title               VARCHAR(255)    NOT NULL,
    message             TEXT            NOT NULL,
    read_at             DATETIME(6)     NULL,
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_inbox_notifications PRIMARY KEY (id),
    INDEX idx_inbox_user (user_id),
    INDEX idx_inbox_read (user_id, read_at),
    INDEX idx_inbox_created (created_at),
    CONSTRAINT fk_inbox_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- user_notification_preferences
-- Allows users to control notification delivery per channel.
-- =====================================================================
CREATE TABLE user_notification_preferences (
    user_id                     CHAR(26)    NOT NULL,
    email_enabled               BOOLEAN     NOT NULL DEFAULT TRUE,
    push_enabled                BOOLEAN     NOT NULL DEFAULT TRUE,
    in_app_enabled              BOOLEAN     NOT NULL DEFAULT TRUE,
    notify_on_transfer_received BOOLEAN     NOT NULL DEFAULT TRUE,
    notify_on_download_complete BOOLEAN     NOT NULL DEFAULT TRUE,
    notify_on_transfer_expiring BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at                  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_user_notification_prefs PRIMARY KEY (user_id),
    CONSTRAINT fk_user_prefs_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

