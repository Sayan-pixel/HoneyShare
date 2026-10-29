package com.honeyshare.encryption.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Stores a user-controlled encrypted recovery envelope.
 * The server stores ONLY ciphertext encrypted using an Argon2id key derived
 * exclusively on the user's client from their recovery secret.
 * The server NEVER possesses or stores the recovery secret or plaintext keys.
 */
@Entity
@Table(name = "user_e2e_recovery_packages")
public class UserRecoveryPackage {

    @Id
    @Column(name = "user_id", length = 26, nullable = false)
    private String userId;

    @Column(name = "encrypted_package_b64", nullable = false, columnDefinition = "TEXT")
    private String encryptedPackageB64;

    @Column(name = "kdf_salt_b64", length = 64, nullable = false)
    private String kdfSaltB64;

    @Column(name = "kdf_algorithm", length = 32, nullable = false)
    private String kdfAlgorithm;

    @Column(name = "kdf_iterations", nullable = false)
    private int kdfIterations;

    @Column(name = "kdf_memory_kib", nullable = false)
    private int kdfMemoryKib;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserRecoveryPackage() {}

    public static UserRecoveryPackage create(
            String userId,
            String encryptedPackageB64,
            String kdfSaltB64,
            String kdfAlgorithm,
            int kdfIterations,
            int kdfMemoryKib
    ) {
        if (encryptedPackageB64 == null || encryptedPackageB64.isBlank()) {
            throw new IllegalArgumentException("Encrypted recovery package must not be blank");
        }
        if (encryptedPackageB64.contains("PRIVATE KEY") || encryptedPackageB64.contains("BEGIN EC PRIVATE")) {
            throw new SecurityException("CRITICAL: Server rejected unencrypted private key material in recovery package!");
        }

        UserRecoveryPackage p = new UserRecoveryPackage();
        p.userId = userId;
        p.encryptedPackageB64 = encryptedPackageB64;
        p.kdfSaltB64 = kdfSaltB64;
        p.kdfAlgorithm = kdfAlgorithm != null ? kdfAlgorithm : "ARGON2ID";
        p.kdfIterations = kdfIterations > 0 ? kdfIterations : 3;
        p.kdfMemoryKib = kdfMemoryKib > 0 ? kdfMemoryKib : 65536;
        Instant now = Instant.now();
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }

    public void update(String encryptedPackageB64, String kdfSaltB64, int iterations, int memoryKib) {
        if (encryptedPackageB64.contains("PRIVATE KEY") || encryptedPackageB64.contains("BEGIN EC PRIVATE")) {
            throw new SecurityException("CRITICAL: Server rejected unencrypted private key material in recovery package!");
        }
        this.encryptedPackageB64 = encryptedPackageB64;
        this.kdfSaltB64 = kdfSaltB64;
        this.kdfIterations = iterations;
        this.kdfMemoryKib = memoryKib;
        this.updatedAt = Instant.now();
    }

    public String getUserId() { return userId; }
    public String getEncryptedPackageB64() { return encryptedPackageB64; }
    public String getKdfSaltB64() { return kdfSaltB64; }
    public String getKdfAlgorithm() { return kdfAlgorithm; }
    public int getKdfIterations() { return kdfIterations; }
    public int getKdfMemoryKib() { return kdfMemoryKib; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

