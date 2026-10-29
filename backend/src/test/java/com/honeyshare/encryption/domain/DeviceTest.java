package com.honeyshare.encryption.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Device Domain Unit Tests")
class DeviceTest {

    private static final String VALID_PUBKEY = "MCowBQYDK2VuAyEA/r8e1f57mK8c5r8Fq7Z3E4N7uG6d1x9L+2o=";

    @Test
    @DisplayName("Registers device successfully with valid public key")
    void registersDeviceSuccessfully() {
        Device device = Device.register("user-1", "MacBook Pro", "MACOS", VALID_PUBKEY);

        assertThat(device.getId()).isNotNull().hasSize(26);
        assertThat(device.getUserId()).isEqualTo("user-1");
        assertThat(device.getDeviceName()).isEqualTo("MacBook Pro");
        assertThat(device.getPlatform()).isEqualTo("MACOS");
        assertThat(device.getPublicKeyB64()).isEqualTo(VALID_PUBKEY);
        assertThat(device.getRegisteredAt()).isNotNull();
        assertThat(device.getLastSeenAt()).isNotNull();
        assertThat(device.getRevokedAt()).isNull();
        assertThat(device.isRevoked()).isFalse();
    }

    @Test
    @DisplayName("Rejects null or blank public key")
    void rejectsEmptyPublicKey() {
        assertThatThrownBy(() -> Device.register("user-1", "Device", "LINUX", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");

        assertThatThrownBy(() -> Device.register("user-1", "Device", "LINUX", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    @DisplayName("Strict security invariant: Rejects attempt to register private key")
    void rejectsPrivateKeyMaterial() {
        String privateKeyPem = "-----BEGIN PRIVATE KEY-----\nMIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg...\n-----END PRIVATE KEY-----";

        assertThatThrownBy(() -> Device.register("user-1", "Device", "LINUX", privateKeyPem))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Server rejected attempt to upload private key material");

        String ecPrivateKey = "-----BEGIN EC PRIVATE KEY-----\nMHcCAQEEI...\n-----END EC PRIVATE KEY-----";
        assertThatThrownBy(() -> Device.register("user-1", "Device", "LINUX", ecPrivateKey))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Server rejected attempt to upload private key material");
    }

    @Test
    @DisplayName("Revoke marks revokedAt timestamp and isRevoked is true")
    void revokesDevice() {
        Device device = Device.register("user-1", "Windows PC", "WINDOWS", VALID_PUBKEY);
        Instant revokeTime = Instant.now();

        device.revoke(revokeTime);

        assertThat(device.isRevoked()).isTrue();
        assertThat(device.getRevokedAt()).isEqualTo(revokeTime);
    }

    @Test
    @DisplayName("Touch updates lastSeenAt timestamp")
    void touchUpdatesLastSeenAt() {
        Device device = Device.register("user-1", "Pixel 8", "ANDROID", VALID_PUBKEY);
        Instant touchTime = Instant.now().plusSeconds(60);

        device.touch(touchTime);

        assertThat(device.getLastSeenAt()).isEqualTo(touchTime);
    }
}

