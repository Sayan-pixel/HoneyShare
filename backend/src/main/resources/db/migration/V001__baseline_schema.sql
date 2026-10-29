# HoneyShare Backend — Flyway Migration V001
# Baseline schema: users, invitations, mfa_methods, devices, audit_events
# All timestamps are UTC DATETIME(6) — never store local time.
# All IDs are CHAR(26) — ULID encoded as Crockford Base32.

-- =====================================================================
-- users
-- =====================================================================
CREATE TABLE users (
    id                  CHAR(26)        NOT NULL,
    email_normalized    VARCHAR(320)    NOT NULL,
    password_hash       VARCHAR(512)    NOT NULL
                        COMMENT 'Argon2id hash including algorithm, params, and salt',
    email_verified_at   DATETIME(6)     NULL,
    status              ENUM('INVITED','ACTIVE','SUSPENDED','DISABLED') NOT NULL DEFAULT 'INVITED',
    role                ENUM('USER','ADMIN') NOT NULL DEFAULT 'USER',
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_login_at       DATETIME(6)     NULL,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email_normalized),
    INDEX idx_users_status (status),
    INDEX idx_users_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- invitations
-- =====================================================================
CREATE TABLE invitations (
    id                  CHAR(26)        NOT NULL,
    email_normalized    VARCHAR(320)    NULL
                        COMMENT 'Null means any email may redeem; default is email-bound',
    token_hash          VARCHAR(128)    NOT NULL
                        COMMENT 'SHA-256 hex of the redeemable token — never store raw token',
    created_by_user_id  CHAR(26)        NOT NULL,
    redeemed_by_user_id CHAR(26)        NULL,
    expires_at          DATETIME(6)     NOT NULL,
    redeemed_at         DATETIME(6)     NULL,
    revoked_at          DATETIME(6)     NULL,
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_invitations PRIMARY KEY (id),
    CONSTRAINT uq_invitations_token UNIQUE (token_hash),
    INDEX idx_invitations_email (email_normalized),
    INDEX idx_invitations_expires (expires_at),
    CONSTRAINT fk_invitations_creator FOREIGN KEY (created_by_user_id)
        REFERENCES users (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- email_verification_tokens
-- =====================================================================
CREATE TABLE email_verification_tokens (
    id              CHAR(26)        NOT NULL,
    user_id         CHAR(26)        NOT NULL,
    token_hash      VARCHAR(128)    NOT NULL
                    COMMENT 'SHA-256 hex of the token',
    expires_at      DATETIME(6)     NOT NULL,
    used_at         DATETIME(6)     NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_email_ver PRIMARY KEY (id),
    CONSTRAINT uq_email_ver_token UNIQUE (token_hash),
    INDEX idx_email_ver_user (user_id),
    INDEX idx_email_ver_expires (expires_at),
    CONSTRAINT fk_email_ver_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- password_reset_tokens
-- =====================================================================
CREATE TABLE password_reset_tokens (
    id              CHAR(26)        NOT NULL,
    user_id         CHAR(26)        NOT NULL,
    token_hash      VARCHAR(128)    NOT NULL
                    COMMENT 'SHA-256 hex of the token',
    expires_at      DATETIME(6)     NOT NULL,
    used_at         DATETIME(6)     NULL,
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_pwd_reset PRIMARY KEY (id),
    CONSTRAINT uq_pwd_reset_token UNIQUE (token_hash),
    INDEX idx_pwd_reset_user (user_id),
    INDEX idx_pwd_reset_expires (expires_at),
    CONSTRAINT fk_pwd_reset_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- user_mfa_methods
-- Never store MFA secrets in plaintext.
-- =====================================================================
CREATE TABLE user_mfa_methods (
    id                  CHAR(26)        NOT NULL,
    user_id             CHAR(26)        NOT NULL,
    method_type         ENUM('TOTP','RECOVERY_CODE') NOT NULL,
    secret_ciphertext   TEXT            NULL
                        COMMENT 'Encrypted TOTP secret — key held server-side in env/secret-manager',
    code_hash           VARCHAR(128)    NULL
                        COMMENT 'For recovery codes: bcrypt hash of the code',
    enabled_at          DATETIME(6)     NULL,
    last_used_at        DATETIME(6)     NULL,
    used_at             DATETIME(6)     NULL
                        COMMENT 'For one-time recovery codes: when it was consumed',
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_mfa PRIMARY KEY (id),
    INDEX idx_mfa_user (user_id),
    INDEX idx_mfa_type (user_id, method_type),
    CONSTRAINT fk_mfa_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- devices (public key registry for future E2E)
-- =====================================================================
CREATE TABLE devices (
    id              CHAR(26)        NOT NULL,
    user_id         CHAR(26)        NOT NULL,
    device_name     VARCHAR(255)    NOT NULL,
    platform        VARCHAR(64)     NOT NULL,
    public_key_b64  TEXT            NULL
                    COMMENT 'Base64-encoded public key — for E2E protocol (feature-flagged)',
    registered_at   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_seen_at    DATETIME(6)     NULL,
    revoked_at      DATETIME(6)     NULL,
    CONSTRAINT pk_devices PRIMARY KEY (id),
    INDEX idx_devices_user (user_id),
    CONSTRAINT fk_devices_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- audit_events
-- Minimal structured security event log — no secrets, no file content.
-- =====================================================================
CREATE TABLE audit_events (
    id              CHAR(26)        NOT NULL,
    actor_user_id   CHAR(26)        NULL        COMMENT 'NULL for system actions',
    event_type      VARCHAR(100)    NOT NULL,
    target_type     VARCHAR(100)    NULL,
    target_id       VARCHAR(128)    NULL,
    correlation_id  VARCHAR(128)    NULL,
    ip_address      VARCHAR(45)     NULL        COMMENT 'IPv4 or IPv6 — redact for privacy',
    metadata_json   JSON            NULL        COMMENT 'Non-secret structured context',
    created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_audit PRIMARY KEY (id),
    INDEX idx_audit_actor (actor_user_id),
    INDEX idx_audit_event (event_type),
    INDEX idx_audit_created (created_at),
    INDEX idx_audit_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

