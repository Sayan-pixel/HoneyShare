package com.honeyshare.identity.domain;

import com.honeyshare.common.UlidGenerator;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * A single-use, time-limited invitation token that allows a specific email
 * address to register on the platform.
 *
 * <p>The raw redeemable token is never stored. Only its SHA-256 hex hash
 * ({@code tokenHash}) is persisted. The raw token is sent once over a
 * secure channel (invitation email) and immediately discarded server-side.
 */
@Entity
@Table(name = "invitations")
public class Invitation {

    @Id
    @Column(length = 26, nullable = false, updatable = false)
    private String id;

    @Column(name = "email_normalized", length = 320)
    private String emailNormalized;

    @Column(name = "token_hash", length = 128, nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "created_by_user_id", length = 26)
    private String createdByUserId;

    @Column(name = "redeemed_by_user_id", length = 26)
    private String redeemedByUserId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "redeemed_at")
    private Instant redeemedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Invitation() {}

    /**
     * Factory — create a new email-bound, single-use invitation.
     *
     * @param emailNormalized  target email in lowercase
     * @param tokenHash        SHA-256 hex of the raw redeemable token
     * @param createdByUserId  ID of the administrator creating the invite
     * @param expiresAt        expiry timestamp
     */
    public static Invitation create(
            String emailNormalized,
            String tokenHash,
            String createdByUserId,
            Instant expiresAt
    ) {
        Invitation inv = new Invitation();
        inv.id = UlidGenerator.generate();
        inv.emailNormalized = emailNormalized;
        inv.tokenHash = tokenHash;
        inv.createdByUserId = createdByUserId;
        inv.expiresAt = expiresAt;
        inv.createdAt = Instant.now();
        return inv;
    }

    // ----------------------------------------------------------------
    // Domain behaviour
    // ----------------------------------------------------------------

    public boolean isUsable() {
        return revokedAt == null
                && redeemedAt == null
                && Instant.now().isBefore(expiresAt);
    }

    /**
     * Checks that the redemption email matches the bound email (case-insensitive).
     * If the invitation is not email-bound ({@code emailNormalized} is null),
     * any email is accepted.
     */
    public boolean isEmailAllowed(String redeemingEmail) {
        if (emailNormalized == null) return true;
        return emailNormalized.equalsIgnoreCase(redeemingEmail);
    }

    public void markRedeemed(String userId, Instant now) {
        this.redeemedByUserId = userId;
        this.redeemedAt = now;
    }

    public void revoke(Instant now) {
        this.revokedAt = now;
    }

    // ----------------------------------------------------------------
    // Getters
    // ----------------------------------------------------------------

    public String getId() { return id; }
    public String getEmailNormalized() { return emailNormalized; }
    public String getTokenHash() { return tokenHash; }
    public String getCreatedByUserId() { return createdByUserId; }
    public String getRedeemedByUserId() { return redeemedByUserId; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRedeemedAt() { return redeemedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getCreatedAt() { return createdAt; }
}

