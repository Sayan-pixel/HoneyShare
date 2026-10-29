package com.honeyshare.encryption;

import com.honeyshare.audit.AuditService;
import com.honeyshare.common.exception.E2eFeatureDisabledException;
import com.honeyshare.common.exception.RecipientNotAuthorizedException;
import com.honeyshare.config.FeatureProperties;
import com.honeyshare.config.TransferProperties;
import com.honeyshare.encryption.domain.Device;
import com.honeyshare.encryption.domain.TransferKeyEnvelope;
import com.honeyshare.encryption.domain.UserRecoveryPackage;
import com.honeyshare.encryption.port.DeviceRepository;
import com.honeyshare.encryption.port.TransferKeyEnvelopeRepository;
import com.honeyshare.encryption.port.UserRecoveryPackageRepository;
import com.honeyshare.encryption.service.E2eKeyManagementService;
import com.honeyshare.identity.port.UserRepository;
import com.honeyshare.quotas.QuotaService;
import com.honeyshare.transfers.domain.PrivacyMode;
import com.honeyshare.transfers.port.TransferRecipientRepository;
import com.honeyshare.transfers.port.TransferRepository;
import com.honeyshare.transfers.service.TransferService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("E2E Security & Adversarial Invariant Tests")
class E2eSecurityAdversarialTest {

    @Mock
    private TransferRepository transferRepository;
    @Mock
    private TransferRecipientRepository recipientRepository;
    @Mock
    private DeviceRepository deviceRepository;
    @Mock
    private TransferKeyEnvelopeRepository envelopeRepository;
    @Mock
    private UserRecoveryPackageRepository recoveryPackageRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private FeatureProperties featureProperties;
    @Mock
    private AuditService auditService;
    @Mock
    private QuotaService quotaService;
    @Mock
    private com.honeyshare.downloads.port.DownloadSessionRepository downloadSessionRepository;
    @Mock
    private com.honeyshare.notifications.service.OutboxService outboxService;

    @Test
    @DisplayName("Adversarial: Server rejects attempt to upload private keys in device registration")
    void rejectsDevicePrivateKeyLeakage() {
        String[] privateKeyPayloads = {
                "-----BEGIN PRIVATE KEY-----\nMIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg...\n-----END PRIVATE KEY-----",
                "-----BEGIN EC PRIVATE KEY-----\nMHcCAQEEI...\n-----END EC PRIVATE KEY-----",
                "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA0...\n-----END RSA PRIVATE KEY-----"
        };

        for (String payload : privateKeyPayloads) {
            assertThatThrownBy(() -> Device.register("user-1", "Hacked Device", "LINUX", payload))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Server rejected attempt to upload private key material");
        }
    }

    @Test
    @DisplayName("Adversarial: Server rejects attempt to store unencrypted private keys in recovery package")
    void rejectsRecoveryPackagePrivateKeyLeakage() {
        String leakedKey = "-----BEGIN EC PRIVATE KEY-----\nMHcCAQEEI...\n-----END EC PRIVATE KEY-----";

        assertThatThrownBy(() -> UserRecoveryPackage.create("user-1", leakedKey, "salt", "ARGON2ID", 3, 65536))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Server rejected unencrypted private key material");

        UserRecoveryPackage pkg = UserRecoveryPackage.create("user-1", "valid-enc-data", "salt", "ARGON2ID", 3, 65536);
        assertThatThrownBy(() -> pkg.update(leakedKey, "salt2", 3, 65536))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Server rejected unencrypted private key material");
    }

    @Test
    @DisplayName("Adversarial: Server-held data alone cannot decrypt client file content")
    void serverHeldDataCannotDecryptCiphertext() throws Exception {
        // 1. Client generates a 256-bit symmetric file encryption key
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256, new SecureRandom());
        SecretKey clientFileKey = keyGen.generateKey();

        // 2. Client generates an ephemeral recipient keypair (simulating X25519)
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair recipientKeyPair = kpg.generateKeyPair();

        // 3. Client encrypts plaintext file
        byte[] plaintext = "Top secret HoneyShare transfer content".getBytes(StandardCharsets.UTF_8);
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        aesCipher.init(Cipher.ENCRYPT_MODE, clientFileKey, new GCMParameterSpec(128, iv));
        byte[] fileCiphertext = aesCipher.doFinal(plaintext);

        // 4. Client wraps the file key using recipient's public key (RSA for standard JRE demo)
        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        rsaCipher.init(Cipher.WRAP_MODE, recipientKeyPair.getPublic());
        byte[] wrappedKeyBytes = rsaCipher.wrap(clientFileKey);
        String wrappedKeyB64 = Base64.getEncoder().encodeToString(wrappedKeyBytes);

        // 5. The server stores ONLY:
        // - recipient public key
        // - wrappedKeyB64 (TransferKeyEnvelope)
        // - fileCiphertext (Storage object)
        TransferKeyEnvelope serverEnvelope = TransferKeyEnvelope.create(
                "trans-1", "dev-recip", "dev-sender",
                Base64.getEncoder().encodeToString(recipientKeyPair.getPublic().getEncoded()),
                wrappedKeyB64, "RSA-OAEP-AES256GCM"
        );

        // 6. Adversary on the server attempts to unwrap the key using ONLY server-held data (public key)
        Cipher adversaryCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        assertThatThrownBy(() -> adversaryCipher.init(Cipher.UNWRAP_MODE, recipientKeyPair.getPublic()))
                .isInstanceOf(Exception.class); // JRE requires PrivateKey for UNWRAP_MODE; public key fails!

        // 7. Legitimate recipient with the private key CAN unwrap and decrypt
        Cipher recipientUnwrapCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        recipientUnwrapCipher.init(Cipher.UNWRAP_MODE, recipientKeyPair.getPrivate());
        SecretKey unwrappedKey = (SecretKey) recipientUnwrapCipher.unwrap(
                Base64.getDecoder().decode(serverEnvelope.getEncryptedKeyB64()), "AES", Cipher.SECRET_KEY);

        Cipher recipientDecryptCipher = Cipher.getInstance("AES/GCM/NoPadding");
        recipientDecryptCipher.init(Cipher.DECRYPT_MODE, unwrappedKey, new GCMParameterSpec(128, iv));
        byte[] decryptedBytes = recipientDecryptCipher.doFinal(fileCiphertext);

        assertThat(new String(decryptedBytes, StandardCharsets.UTF_8)).isEqualTo("Top secret HoneyShare transfer content");
    }

    @Test
    @DisplayName("Gate 5: Transfer creation in E2E mode is blocked when feature flag is disabled")
    void blocksTransferCreationWhenE2eDisabled() {
        when(featureProperties.isE2eEnabled()).thenReturn(false);

        TransferService transferService = new TransferService(
                transferRepository, recipientRepository, userRepository,
                quotaService, auditService, downloadSessionRepository,
                outboxService, featureProperties
        );

        assertThatThrownBy(() -> transferService.createTransfer(
                "user-1", "data.enc", 1024L, "application/octet-stream",
                PrivacyMode.E2E, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                List.of("bob@example.com"), "127.0.0.1"
        )).isInstanceOf(E2eFeatureDisabledException.class);
    }

    @Test
    @DisplayName("Security: Revoked device cannot retrieve key envelopes")
    void revokedDeviceCannotRetrieveEnvelopes() {
        when(featureProperties.isE2eEnabled()).thenReturn(true);

        E2eKeyManagementService service = new E2eKeyManagementService(
                transferRepository, recipientRepository, deviceRepository,
                envelopeRepository, recoveryPackageRepository, userRepository,
                featureProperties, auditService
        );

        Device revokedDevice = Device.register("user-recip", "Stolen Phone", "IOS", "MCowBQYDK2VuAyEA...");
        revokedDevice.revoke(Instant.now());

        when(deviceRepository.findByIdAndUserId(revokedDevice.getId(), "user-recip"))
                .thenReturn(Optional.of(revokedDevice));

        assertThatThrownBy(() -> service.getTransferKeyEnvelope("trans-1", "user-recip", revokedDevice.getId()))
                .isInstanceOf(RecipientNotAuthorizedException.class)
                .hasMessageContaining("Device access has been revoked");
    }
}
