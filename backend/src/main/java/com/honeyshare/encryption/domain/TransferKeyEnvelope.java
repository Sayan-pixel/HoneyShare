package com.honeyshare.encryption.domain;

import com.honeyshare.common.UlidGenerator;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Stores the encrypted symmetric data key (wrapped key envelope) for a recipient device.
 * Server stores ONLY ciphertext; never has access to the plaintext data key.
 */
@Entity
@Table(name = "transfer_key_envelopes")
public class TransferKeyEnvelope {

    @Id
    @Column(length = 26, nullable = false, updatable = false)
    private String id;

    @Column(name = "transfer_id", length = 26, nullable = false)
    private String transferId;

    @Column(name = "recipient_device_id", length = 26, nullable = false)
    private String recipientDeviceId;

    @Column(name = "sender_device_id", length = 26, nullable = false)
    private String senderDeviceId;

    @Column(name = "ephemeral_public_key_b64", length = 128, nullable = false)
    private String ephemeralPublicKeyB64;

    @Column(name = "encrypted_key_b64", nullable = false, columnDefinition = "TEXT")
    private String encryptedKeyB64;

    @Column(name = "key_algorithm", length = 64, nullable = false)
    private String keyAlgorithm;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TransferKeyEnvelope() {}

    public static TransferKeyEnvelope create(
            String transferId,
            String recipientDeviceId,
            String senderDeviceId,
            String ephemeralPublicKeyB64,
            String encryptedKeyB64,
            String keyAlgorithm
    ) {
        if (encryptedKeyB64 == null || encryptedKeyB64.isBlank()) {
            throw new IllegalArgumentException("Encrypted key envelope cannot be blank");
        }

        TransferKeyEnvelope env = new TransferKeyEnvelope();
        env.id = UlidGenerator.generate();
        env.transferId = transferId;
        env.recipientDeviceId = recipientDeviceId;
        env.senderDeviceId = senderDeviceId;
        env.ephemeralPublicKeyB64 = ephemeralPublicKeyB64;
        env.encryptedKeyB64 = encryptedKeyB64;
        env.keyAlgorithm = keyAlgorithm != null ? keyAlgorithm : "X25519-HKDF-AES256GCM";
        env.createdAt = Instant.now();
        return env;
    }

    public String getId() { return id; }
    public String getTransferId() { return transferId; }
    public String getRecipientDeviceId() { return recipientDeviceId; }
    public String getSenderDeviceId() { return senderDeviceId; }
    public String getEphemeralPublicKeyB64() { return ephemeralPublicKeyB64; }
    public String getEncryptedKeyB64() { return encryptedKeyB64; }
    public String getKeyAlgorithm() { return keyAlgorithm; }
    public Instant getCreatedAt() { return createdAt; }
}

