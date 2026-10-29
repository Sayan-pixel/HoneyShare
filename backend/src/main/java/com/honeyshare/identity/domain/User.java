package com.honeyshare.identity.domain;

import com.honeyshare.common.UlidGenerator;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * A registered user of the platform.
 *
 * <p>Password hash uses Argon2id; raw password is never stored here.
 * Email is stored in lower-case normalized form.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(length = 26, nullable = false, updatable = false)
    private String id;

    @Column(name = "email_normalized", length = 320, nullable = false, unique = true)
    private String emailNormalized;

    @Column(name = "password_hash", length = 512, nullable = false)
    private String passwordHash;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 10)
    private UserRole role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected User() {}

    /**
     * Factory — creates a new user with INVITED status.
     * The caller is responsible for recording the audit event.
     */
    public static User create(String emailNormalized, String passwordHash, UserRole role) {
        User u = new User();
        u.id = UlidGenerator.generate();
        u.emailNormalized = emailNormalized;
        u.passwordHash = passwordHash;
        u.status = UserStatus.INVITED;
        u.role = role;
        Instant now = Instant.now();
        u.createdAt = now;
        u.updatedAt = now;
        return u;
    }

    // ----------------------------------------------------------------
    // Domain behaviour
    // ----------------------------------------------------------------

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public boolean canAccessFiles() {
        return isEmailVerified() && isActive();
    }

    public void verifyEmail(Instant now) {
        if (this.emailVerifiedAt == null) {
            this.emailVerifiedAt = now;
            this.status = UserStatus.ACTIVE;
            this.updatedAt = now;
        }
    }

    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
        this.updatedAt = now;
    }

    public void updatePasswordHash(String newHash, Instant now) {
        this.passwordHash = newHash;
        this.updatedAt = now;
    }

    // ----------------------------------------------------------------
    // Getters (no public setters — mutate via domain methods)
    // ----------------------------------------------------------------

    public String getId() { return id; }
    public String getEmailNormalized() { return emailNormalized; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
    public UserStatus getStatus() { return status; }
    public UserRole getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }
}

