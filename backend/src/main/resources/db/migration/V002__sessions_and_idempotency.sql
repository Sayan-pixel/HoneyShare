-- V002: Sessions and idempotency records

-- =====================================================================
-- user_sessions
-- Stateful DB-backed sessions allow immediate revocation.
-- =====================================================================
CREATE TABLE user_sessions (
    id                  CHAR(26)        NOT NULL,
    user_id             CHAR(26)        NOT NULL,
    session_token_hash  VARCHAR(128)    NOT NULL
                        COMMENT 'SHA-256 hex of the bearer token — never store raw token',
    device_id           CHAR(26)        NULL,
    ip_address          VARCHAR(45)     NULL,
    user_agent          VARCHAR(512)    NULL,
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at          DATETIME(6)     NOT NULL,
    last_used_at        DATETIME(6)     NULL,
    revoked_at          DATETIME(6)     NULL,
    mfa_verified_at     DATETIME(6)     NULL
                        COMMENT 'Timestamp when MFA was verified in this session',
    CONSTRAINT pk_sessions PRIMARY KEY (id),
    CONSTRAINT uq_sessions_token UNIQUE (session_token_hash),
    INDEX idx_sessions_user (user_id),
    INDEX idx_sessions_expires (expires_at),
    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- idempotency_records
-- Tracks completed mutating operations so retries return prior result.
-- NEVER store bearer tokens or sensitive payloads here.
-- =====================================================================
CREATE TABLE idempotency_records (
    id                  CHAR(26)        NOT NULL,
    user_id             CHAR(26)        NOT NULL,
    operation_scope     VARCHAR(100)    NOT NULL,
    idempotency_key     VARCHAR(128)    NOT NULL,
    request_fingerprint VARCHAR(128)    NULL
                        COMMENT 'Hash of request body for conflict detection',
    response_status     SMALLINT        NULL        COMMENT 'HTTP status code',
    response_reference  VARCHAR(512)    NULL        COMMENT 'Resource ID or safe summary',
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at          DATETIME(6)     NOT NULL,
    CONSTRAINT pk_idempotency PRIMARY KEY (id),
    CONSTRAINT uq_idempotency UNIQUE (user_id, operation_scope, idempotency_key),
    INDEX idx_idempotency_expires (expires_at),
    CONSTRAINT fk_idempotency_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

