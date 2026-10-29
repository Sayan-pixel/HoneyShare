-- V003: Core Transfers, Upload Sessions, File Chunks, Quota Reservations, and Background Jobs
-- Complies with HoneyShare Specification Section 8 & Section 9.

-- =====================================================================
-- transfers
-- =====================================================================
CREATE TABLE transfers (
    id                      CHAR(26)        NOT NULL,
    sender_id               CHAR(26)        NOT NULL,
    original_filename       VARCHAR(255)    NOT NULL
                            COMMENT 'Sanitized original filename for display',
    file_size_bytes         BIGINT          NOT NULL,
    content_type_hint       VARCHAR(128)    NULL
                            COMMENT 'Untrusted content-type hint provided by client',
    privacy_mode            ENUM('STANDARD','E2E') NOT NULL DEFAULT 'STANDARD',
    state                   ENUM(
                                'CREATED',
                                'QUEUED',
                                'UPLOADING',
                                'VERIFYING',
                                'STORED_AND_VERIFIED',
                                'AVAILABLE_TO_RECIPIENT',
                                'PARTIALLY_DELIVERED',
                                'DELIVERED',
                                'CANCELLED',
                                'EXPIRED',
                                'DELETION_PENDING',
                                'DELETED',
                                'FAILED'
                            ) NOT NULL DEFAULT 'CREATED',
    storage_object_key      VARCHAR(512)    NULL
                            COMMENT 'Opaque S3 object key',
    storage_object_version  VARCHAR(128)    NULL,
    expected_sha256         VARCHAR(64)     NOT NULL
                            COMMENT 'SHA-256 hex expected by sender client (plaintext for STANDARD, ciphertext for E2E)',
    verified_sha256         VARCHAR(64)     NULL
                            COMMENT 'SHA-256 hex calculated and verified by backend after upload completion',
    quota_reservation_bytes BIGINT          NOT NULL DEFAULT 0,
    state_version           BIGINT          NOT NULL DEFAULT 1
                            COMMENT 'Optimistic locking version field',
    created_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    stored_at               DATETIME(6)     NULL
                            COMMENT 'Timestamp when STORED_AND_VERIFIED was reached',
    expires_at              DATETIME(6)     NULL
                            COMMENT 'Calculated from stored_at + retention policy',
    deletion_started_at     DATETIME(6)     NULL,
    deleted_at              DATETIME(6)     NULL,
    CONSTRAINT pk_transfers PRIMARY KEY (id),
    INDEX idx_transfers_sender (sender_id),
    INDEX idx_transfers_state (state),
    INDEX idx_transfers_expires (expires_at),
    CONSTRAINT fk_transfers_sender FOREIGN KEY (sender_id)
        REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- transfer_recipients
-- =====================================================================
CREATE TABLE transfer_recipients (
    id                  CHAR(26)        NOT NULL,
    transfer_id         CHAR(26)        NOT NULL,
    recipient_user_id   CHAR(26)        NOT NULL,
    state               ENUM(
                            'INVITATION_REQUIRED',
                            'AWAITING_EMAIL_VERIFICATION',
                            'AVAILABLE',
                            'DOWNLOAD_STARTED',
                            'DOWNLOADING',
                            'VERIFIED_AT_RECEIVER',
                            'CANCELLED',
                            'EXPIRED',
                            'FAILED'
                        ) NOT NULL DEFAULT 'AVAILABLE',
    invitation_id       CHAR(26)        NULL,
    available_at        DATETIME(6)     NULL,
    download_started_at DATETIME(6)     NULL,
    verified_at         DATETIME(6)     NULL
                        COMMENT 'Timestamp when recipient acknowledged verified download',
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_transfer_recipients PRIMARY KEY (id),
    CONSTRAINT uq_transfer_recipients UNIQUE (transfer_id, recipient_user_id),
    INDEX idx_recipients_user (recipient_user_id),
    INDEX idx_recipients_state (state),
    CONSTRAINT fk_recipients_transfer FOREIGN KEY (transfer_id)
        REFERENCES transfers (id) ON DELETE CASCADE,
    CONSTRAINT fk_recipients_user FOREIGN KEY (recipient_user_id)
        REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- upload_sessions
-- =====================================================================
CREATE TABLE upload_sessions (
    id                   CHAR(26)        NOT NULL,
    transfer_id          CHAR(26)        NOT NULL,
    storage_upload_id    VARCHAR(256)    NULL
                         COMMENT 'Multipart upload ID from S3/MinIO',
    state                ENUM(
                            'INITIATED',
                            'ACTIVE',
                            'PAUSED',
                            'COMPLETING',
                            'VERIFYING',
                            'COMPLETED',
                            'ABORT_PENDING',
                            'ABORTED',
                            'FAILED'
                         ) NOT NULL DEFAULT 'INITIATED',
    chunk_size_bytes     BIGINT          NOT NULL,
    expected_chunk_count INT             NOT NULL,
    failure_code         VARCHAR(100)    NULL,
    created_at           DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_activity_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at         DATETIME(6)     NULL,
    CONSTRAINT pk_upload_sessions PRIMARY KEY (id),
    INDEX idx_upload_sessions_transfer (transfer_id),
    INDEX idx_upload_sessions_state (state),
    CONSTRAINT fk_upload_sessions_transfer FOREIGN KEY (transfer_id)
        REFERENCES transfers (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- file_chunks
-- =====================================================================
CREATE TABLE file_chunks (
    id                  CHAR(26)        NOT NULL,
    upload_session_id   CHAR(26)        NOT NULL,
    chunk_index         INT             NOT NULL
                        COMMENT '1-based part number matching S3 multipart partNumber',
    byte_length         BIGINT          NOT NULL,
    checksum_algorithm  VARCHAR(32)     NOT NULL DEFAULT 'SHA-256',
    checksum_value      VARCHAR(128)    NULL
                        COMMENT 'Hex checksum value supplied by client upon commit',
    storage_part_id     VARCHAR(256)    NULL
                        COMMENT 'ETag returned by object storage for this part',
    state               ENUM('PENDING','UPLOADING','COMMITTED','VERIFIED','FAILED') NOT NULL DEFAULT 'PENDING',
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    verified_at         DATETIME(6)     NULL,
    CONSTRAINT pk_file_chunks PRIMARY KEY (id),
    CONSTRAINT uq_session_chunk UNIQUE (upload_session_id, chunk_index),
    INDEX idx_chunks_session (upload_session_id),
    CONSTRAINT fk_chunks_session FOREIGN KEY (upload_session_id)
        REFERENCES upload_sessions (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- quota_reservations
-- Prevents concurrent upload races from exceeding 200 GB quota limit.
-- =====================================================================
CREATE TABLE quota_reservations (
    id              CHAR(26)        NOT NULL,
    user_id         CHAR(26)        NOT NULL,
    transfer_id     CHAR(26)        NOT NULL,
    reserved_bytes  BIGINT          NOT NULL,
    state           ENUM('ACTIVE','COMMITTED','RELEASED') NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    released_at     DATETIME(6)     NULL,
    CONSTRAINT pk_quota_reservations PRIMARY KEY (id),
    INDEX idx_quota_user (user_id),
    INDEX idx_quota_transfer (transfer_id),
    INDEX idx_quota_state (state),
    CONSTRAINT fk_quota_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_quota_transfer FOREIGN KEY (transfer_id)
        REFERENCES transfers (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- background_jobs
-- Durable database-backed job queue for retries, reconciliation, and deletion.
-- =====================================================================
CREATE TABLE background_jobs (
    id               CHAR(26)        NOT NULL,
    job_type         VARCHAR(100)    NOT NULL,
    aggregate_id     VARCHAR(128)    NOT NULL,
    state            ENUM('PENDING','RUNNING','COMPLETED','FAILED','DEAD_LETTER') NOT NULL DEFAULT 'PENDING',
    attempt_count    INT             NOT NULL DEFAULT 0,
    max_attempts     INT             NOT NULL DEFAULT 5,
    next_attempt_at  DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    locked_at        DATETIME(6)     NULL,
    locked_by        VARCHAR(128)    NULL,
    last_error_code  VARCHAR(100)    NULL,
    last_error_msg   TEXT            NULL,
    created_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_background_jobs PRIMARY KEY (id),
    INDEX idx_jobs_state_next (state, next_attempt_at),
    INDEX idx_jobs_aggregate (aggregate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

