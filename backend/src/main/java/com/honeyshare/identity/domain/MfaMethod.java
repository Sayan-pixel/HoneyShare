package com.honeyshare.identity.domain;

import com.honeyshare.common.UlidGenerator;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * An MFA method registered for a user (TOTP or recovery code).
 *
 * <p>TOTP secrets are stored encrypted (never plaintext). Recovery code
 * hashes are stored using bcrypt/argon2; raw codes are shown once and
 * never stored.
 */
@Entity
@Table(name = "user_mfa_methods")
public class MfaMethod {

    @Id
    @Column(length = 26, nullable = false, updatable = false)
    private String id;

    @Column(name = "user_id", length = 26, nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "method_type", nullable = false, length = 20)
    private MfaMethodType methodType;

    /** Encrypted TOTP secret (AES-256-GCM envelope). Null for recovery codes. */
    @Column(name = "secret_ciphertext", columnDefinition = "TEXT")
    private String secretCiphertext;

    /** Bcrypt hash of a recovery code. Null for TOTP. */
    @Column(name = "code_hash", length = 128)
    private String codeHash;

    @Column(name = "enabled_at")
    private Instant enabledAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    /** For recovery codes: timestamp when the code was consumed. */
    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MfaMethod() {}

    public static MfaMethod createTotp(String userId, String secretCiphertext) {
        MfaMethod m = new MfaMethod();
        m.id = UlidGenerator.generate();
        m.userId = userId;
        m.methodType = MfaMethodType.TOTP;
        m.secretCiphertext = secretCiphertext;
        m.createdAt = Instant.now();
        return m;
    }

    public static MfaMethod createRecoveryCode(String userId, String codeHash) {
        MfaMethod m = new MfaMethod();
        m.id = UlidGenerator.generate();
        m.userId = userId;
        m.methodType = MfaMethodType.RECOVERY_CODE;
        m.codeHash = codeHash;
        m.createdAt = Instant.now();
        return m;
    }

    public void enable(Instant now) {
        this.enabledAt = now;
    }

    public void recordUse(Instant now) {
        this.lastUsedAt = now;
    }

    public void consumeRecoveryCode(Instant now) {
        this.usedAt = now;
        this.lastUsedAt = now;
    }

    public boolean isEnabled() { return enabledAt != null; }
    public boolean isRecoveryCodeConsumed() { return usedAt != null; }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public MfaMethodType getMethodType() { return methodType; }
    public String getSecretCiphertext() { return secretCiphertext; }
    public String getCodeHash() { return codeHash; }
    public Instant getEnabledAt() { return enabledAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getUsedAt() { return usedAt; }
    public Instant getCreatedAt() { return createdAt; }
}

