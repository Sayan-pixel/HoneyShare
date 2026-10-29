-- V006: E2E Key Envelopes and User-Controlled Recovery Packages
-- Complies with HoneyShare Specification Section 3.3, Section 5 (FR-SEC-03 to FR-SEC-09), Section 9.4, 9.5, and Section 12.2.

-- =====================================================================
-- transfer_key_envelopes
-- Stores wrapped symmetric file encryption keys per recipient device.
-- The server stores only ciphertext key envelopes; never plaintext data keys.
-- =====================================================================
CREATE TABLE transfer_key_envelopes (
    id                          CHAR(26)        NOT NULL,
    transfer_id                 CHAR(26)        NOT NULL,
    recipient_device_id         CHAR(26)        NOT NULL,
    sender_device_id            CHAR(26)        NOT NULL,
    ephemeral_public_key_b64    VARCHAR(128)    NOT NULL
                                COMMENT 'Ephemeral public key used by sender client for ECDH key agreement',
    encrypted_key_b64           TEXT            NOT NULL
                                COMMENT 'Ciphertext of the file data key wrapped with recipient device public key',
    key_algorithm               VARCHAR(64)     NOT NULL DEFAULT 'X25519-HKDF-AES256GCM',
    created_at                  DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_key_envelopes PRIMARY KEY (id),
    CONSTRAINT uq_envelope_recipient UNIQUE (transfer_id, recipient_device_id),
    INDEX idx_envelopes_transfer (transfer_id),
    INDEX idx_envelopes_recipient_device (recipient_device_id),
    CONSTRAINT fk_envelope_transfer FOREIGN KEY (transfer_id)
        REFERENCES transfers (id) ON DELETE CASCADE,
    CONSTRAINT fk_envelope_recipient_device FOREIGN KEY (recipient_device_id)
        REFERENCES devices (id) ON DELETE CASCADE,
    CONSTRAINT fk_envelope_sender_device FOREIGN KEY (sender_device_id)
        REFERENCES devices (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =====================================================================
-- user_e2e_recovery_packages
-- User-controlled encrypted recovery envelope.
-- The server NEVER receives or stores the recovery secret or plaintext private key.
-- =====================================================================
CREATE TABLE user_e2e_recovery_packages (
    user_id                 CHAR(26)        NOT NULL,
    encrypted_package_b64   TEXT            NOT NULL
                            COMMENT 'Root key/device keys encrypted client-side using Argon2id derived key',
    kdf_salt_b64            VARCHAR(64)     NOT NULL,
    kdf_algorithm           VARCHAR(32)     NOT NULL DEFAULT 'ARGON2ID',
    kdf_iterations          INT             NOT NULL DEFAULT 3,
    kdf_memory_kib          INT             NOT NULL DEFAULT 65536,
    created_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_recovery_packages PRIMARY KEY (user_id),
    CONSTRAINT fk_recovery_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

