package com.honeyshare.encryption.domain;

import com.honeyshare.common.UlidGenerator;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Registered user device holding a cryptographic public key for E2E mode.
 * The server stores ONLY public keys; private keys are stored exclusively in the client's secure enclave.
 */
@Entity
@Table(name = "devices")
public class Device {

    @Id
    @Column(length = 26, nullable = false, updatable = false)
    private String id;

    @Column(name = "user_id", length = 26, nullable = false)
    private String userId;

    @Column(name = "device_name", length = 255, nullable = false)
    private String deviceName;

    @Column(name = "platform", length = 64, nullable = false)
    private String platform;

    @Column(name = "public_key_b64", columnDefinition = "TEXT")
    private String publicKeyB64;

    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected Device() {}

    public static Device register(String userId, String deviceName, String platform, String publicKeyB64) {
        if (publicKeyB64 == null || publicKeyB64.isBlank()) {
            throw new IllegalArgumentException("Device public key must not be empty");
        }
        // Strict security assertion: Never store private keys on the server!
        if (publicKeyB64.contains("PRIVATE KEY") || publicKeyB64.contains("BEGIN EC PRIVATE") || publicKeyB64.contains("BEGIN RSA PRIVATE")) {
            throw new SecurityException("CRITICAL: Server rejected attempt to upload private key material!");
        }

        Device d = new Device();
        d.id = UlidGenerator.generate();
        d.userId = userId;
        d.deviceName = deviceName != null ? deviceName.trim() : "Unknown Device";
        d.platform = platform != null ? platform.trim() : "UNKNOWN";
        d.publicKeyB64 = publicKeyB64.trim();
        Instant now = Instant.now();
        d.registeredAt = now;
        d.lastSeenAt = now;
        return d;
    }

    public void revoke(Instant now) {
        this.revokedAt = now;
    }

    public void touch(Instant now) {
        this.lastSeenAt = now;
    }

    public boolean isRevoked() {
        return this.revokedAt != null;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getDeviceName() { return deviceName; }
    public String getPlatform() { return platform; }
    public String getPublicKeyB64() { return publicKeyB64; }
    public Instant getRegisteredAt() { return registeredAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Instant getRevokedAt() { return revokedAt; }
}

