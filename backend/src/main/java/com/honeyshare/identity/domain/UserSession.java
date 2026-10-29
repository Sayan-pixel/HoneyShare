package com.honeyshare.identity.domain;

import com.honeyshare.common.UlidGenerator;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * A server-side session bound to a user.
 *
 * <p>Only a SHA-256 hash of the bearer token is stored.
 * The raw bearer token is returned once at login and never stored.
 */
@Entity
@Table(name = "user_sessions")
public class UserSession {

    @Id
    @Column(length = 26, nullable = false, updatable = false)
    private String id;

    @Column(name = "user_id", length = 26, nullable = false)
    private String userId;

    @Column(name = "session_token_hash", length = 128, nullable = false, unique = true)
    private String sessionTokenHash;

    @Column(name = "device_id", length = 26)
    private String deviceId;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "mfa_verified_at")
    private Instant mfaVerifiedAt;

    protected UserSession() {}

    public static UserSession create(
            String userId,
            String sessionTokenHash,
            Instant expiresAt,
            String ipAddress,
            String userAgent
    ) {
        UserSession s = new UserSession();
        s.id = UlidGenerator.generate();
        s.userId = userId;
        s.sessionTokenHash = sessionTokenHash;
        s.expiresAt = expiresAt;
        s.ipAddress = ipAddress;
        s.userAgent = userAgent;
        Instant now = Instant.now();
        s.createdAt = now;
        s.lastUsedAt = now;
        return s;
    }

    public boolean isValid() {
        return revokedAt == null && Instant.now().isBefore(expiresAt);
    }

    public void recordUse(Instant now) {
        this.lastUsedAt = now;
    }

    public void markMfaVerified(Instant now) {
        this.mfaVerifiedAt = now;
    }

    public void revoke(Instant now) {
        this.revokedAt = now;
    }

    public boolean isMfaVerified() {
        return mfaVerifiedAt != null;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getSessionTokenHash() { return sessionTokenHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getMfaVerifiedAt() { return mfaVerifiedAt; }
}

