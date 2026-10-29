package com.honeyshare.encryption.service;

import com.honeyshare.audit.AuditService;
import com.honeyshare.common.exception.E2eFeatureDisabledException;
import com.honeyshare.common.exception.EmailNotVerifiedException;
import com.honeyshare.common.exception.RecipientNotAuthorizedException;
import com.honeyshare.common.exception.TransferNotFoundException;
import com.honeyshare.config.FeatureProperties;
import com.honeyshare.encryption.domain.Device;
import com.honeyshare.encryption.domain.TransferKeyEnvelope;
import com.honeyshare.encryption.domain.UserRecoveryPackage;
import com.honeyshare.encryption.port.DeviceRepository;
import com.honeyshare.encryption.port.TransferKeyEnvelopeRepository;
import com.honeyshare.encryption.port.UserRecoveryPackageRepository;
import com.honeyshare.encryption.service.E2eKeyManagementService.EnvelopeSubmission;
import com.honeyshare.encryption.service.E2eKeyManagementService.KeyEnvelopeDto;
import com.honeyshare.encryption.service.E2eKeyManagementService.RecoveryPackageDto;
import com.honeyshare.identity.domain.User;
import com.honeyshare.identity.domain.UserRole;
import com.honeyshare.identity.port.UserRepository;
import com.honeyshare.transfers.domain.PrivacyMode;
import com.honeyshare.transfers.domain.Transfer;
import com.honeyshare.transfers.port.TransferRecipientRepository;
import com.honeyshare.transfers.port.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("E2eKeyManagementService Unit Tests")
class E2eKeyManagementServiceTest {

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

    private E2eKeyManagementService service;

    private static final String PUBKEY = "MCowBQYDK2VuAyEA/r8e1f57mK8c5r8Fq7Z3E4N7uG6d1x9L+2o=";
    private static final String CIPHERTEXT_KEY = "YWVzLTI1Ni1nY20tY2lwaGVydGV4dC1kYXRhLWtleQ==";

    @BeforeEach
    void setUp() {
        service = new E2eKeyManagementService(
                transferRepository,
                recipientRepository,
                deviceRepository,
                envelopeRepository,
                recoveryPackageRepository,
                userRepository,
                featureProperties,
                auditService
        );
    }

    @Test
    @DisplayName("Throws E2eFeatureDisabledException when feature flag is disabled")
    void throwsWhenFeatureDisabled() {
        when(featureProperties.isE2eEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.storeTransferKeyEnvelopes("t1", "u1", "d1", List.of(), "127.0.0.1"))
                .isInstanceOf(E2eFeatureDisabledException.class);

        assertThatThrownBy(() -> service.getTransferKeyEnvelope("t1", "u1", "d1"))
                .isInstanceOf(E2eFeatureDisabledException.class);

        assertThatThrownBy(() -> service.storeRecoveryPackage("u1", "pkg", "salt", 3, 65536, "127.0.0.1"))
                .isInstanceOf(E2eFeatureDisabledException.class);

        assertThatThrownBy(() -> service.getRecoveryPackage("u1"))
                .isInstanceOf(E2eFeatureDisabledException.class);
    }

    @Test
    @DisplayName("Stores key envelope for recipient device when feature is enabled")
    void storesKeyEnvelopeSuccessfully() {
        when(featureProperties.isE2eEnabled()).thenReturn(true);

        Transfer transfer = Transfer.create(
                "user-sender", "secret.zip", 1024L, "application/zip",
                PrivacyMode.E2E, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        when(transferRepository.findById(transfer.getId())).thenReturn(Optional.of(transfer));

        Device recipientDev = Device.register("user-recipient", "iPhone", "IOS", PUBKEY);
        when(deviceRepository.findById(recipientDev.getId())).thenReturn(Optional.of(recipientDev));
        when(envelopeRepository.existsByTransferIdAndRecipientDeviceId(transfer.getId(), recipientDev.getId())).thenReturn(false);

        List<EnvelopeSubmission> submissions = List.of(new EnvelopeSubmission(
                recipientDev.getId(), PUBKEY, CIPHERTEXT_KEY, "X25519-HKDF-AES256GCM"
        ));

        service.storeTransferKeyEnvelopes(transfer.getId(), "user-sender", "dev-sender", submissions, "127.0.0.1");

        verify(envelopeRepository).save(any(TransferKeyEnvelope.class));
        verify(auditService).record(eq("user-sender"), eq("E2E_KEY_ENVELOPES_STORED"), eq("transfer"), eq(transfer.getId()), eq("127.0.0.1"));
    }

    @Test
    @DisplayName("Rejects store envelopes if caller is not the sender")
    void rejectsStoreEnvelopesIfNotSender() {
        when(featureProperties.isE2eEnabled()).thenReturn(true);

        Transfer transfer = Transfer.create(
                "user-sender", "secret.zip", 1024L, "application/zip",
                PrivacyMode.E2E, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        when(transferRepository.findById(transfer.getId())).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> service.storeTransferKeyEnvelopes(
                transfer.getId(), "user-imposter", "dev-1", List.of(), "127.0.0.1"))
                .isInstanceOf(RecipientNotAuthorizedException.class)
                .hasMessageContaining("Only the sender may store key envelopes");
    }

    @Test
    @DisplayName("Rejects store envelopes if transfer is not in E2E mode")
    void rejectsStoreEnvelopesIfNotE2eMode() {
        when(featureProperties.isE2eEnabled()).thenReturn(true);

        Transfer transfer = Transfer.create(
                "user-sender", "doc.pdf", 1024L, "application/pdf",
                PrivacyMode.STANDARD, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        when(transferRepository.findById(transfer.getId())).thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> service.storeTransferKeyEnvelopes(
                transfer.getId(), "user-sender", "dev-1", List.of(), "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Transfer is not in E2E mode");
    }

    @Test
    @DisplayName("Rejects store envelope for revoked recipient device")
    void rejectsStoreEnvelopeForRevokedDevice() {
        when(featureProperties.isE2eEnabled()).thenReturn(true);

        Transfer transfer = Transfer.create(
                "user-sender", "secret.zip", 1024L, "application/zip",
                PrivacyMode.E2E, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        when(transferRepository.findById(transfer.getId())).thenReturn(Optional.of(transfer));

        Device recipientDev = Device.register("user-recipient", "Old Phone", "IOS", PUBKEY);
        recipientDev.revoke(Instant.now());
        when(deviceRepository.findById(recipientDev.getId())).thenReturn(Optional.of(recipientDev));

        List<EnvelopeSubmission> submissions = List.of(new EnvelopeSubmission(
                recipientDev.getId(), PUBKEY, CIPHERTEXT_KEY, "X25519-HKDF-AES256GCM"
        ));

        assertThatThrownBy(() -> service.storeTransferKeyEnvelopes(
                transfer.getId(), "user-sender", "dev-sender", submissions, "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot create envelope for revoked device");
    }

    @Test
    @DisplayName("Recipient retrieves envelope for active device")
    void recipientRetrievesEnvelope() {
        when(featureProperties.isE2eEnabled()).thenReturn(true);

        Device device = Device.register("user-recipient", "Laptop", "LINUX", PUBKEY);
        when(deviceRepository.findByIdAndUserId(device.getId(), "user-recipient")).thenReturn(Optional.of(device));

        TransferKeyEnvelope envelope = TransferKeyEnvelope.create(
                "trans-1", device.getId(), "dev-sender", PUBKEY, CIPHERTEXT_KEY, "X25519-HKDF-AES256GCM");
        when(envelopeRepository.findByTransferIdAndRecipientDeviceId("trans-1", device.getId()))
                .thenReturn(Optional.of(envelope));

        KeyEnvelopeDto dto = service.getTransferKeyEnvelope("trans-1", "user-recipient", device.getId());

        assertThat(dto).isNotNull();
        assertThat(dto.transferId()).isEqualTo("trans-1");
        assertThat(dto.recipientDeviceId()).isEqualTo(device.getId());
        assertThat(dto.encryptedKeyB64()).isEqualTo(CIPHERTEXT_KEY);
    }

    @Test
    @DisplayName("Rejects envelope retrieval if recipient device has been revoked")
    void rejectsEnvelopeRetrievalIfDeviceRevoked() {
        when(featureProperties.isE2eEnabled()).thenReturn(true);

        Device device = Device.register("user-recipient", "Laptop", "LINUX", PUBKEY);
        device.revoke(Instant.now());
        when(deviceRepository.findByIdAndUserId(device.getId(), "user-recipient")).thenReturn(Optional.of(device));

        assertThatThrownBy(() -> service.getTransferKeyEnvelope("trans-1", "user-recipient", device.getId()))
                .isInstanceOf(RecipientNotAuthorizedException.class)
                .hasMessageContaining("Device access has been revoked");
    }

    @Test
    @DisplayName("Stores and updates user-controlled encrypted recovery package")
    void storesAndUpdatesRecoveryPackage() {
        when(featureProperties.isE2eEnabled()).thenReturn(true);

        User user = User.create("alice@example.com", "hash", UserRole.USER);
        user.verifyEmail(Instant.now());
        when(userRepository.findById("user-alice")).thenReturn(Optional.of(user));
        when(recoveryPackageRepository.findByUserId("user-alice")).thenReturn(Optional.empty());

        service.storeRecoveryPackage("user-alice", "enc-pkg-data", "salt-123", 3, 65536, "127.0.0.1");

        verify(recoveryPackageRepository).save(any(UserRecoveryPackage.class));
        verify(auditService).record("user-alice", "E2E_RECOVERY_PACKAGE_STORED", "user", "user-alice", "127.0.0.1");
    }

    @Test
    @DisplayName("Rejects recovery package storage if user email is not verified")
    void rejectsRecoveryPackageIfUnverified() {
        when(featureProperties.isE2eEnabled()).thenReturn(true);

        User user = User.create("bob@example.com", "hash", UserRole.USER); // not verified
        when(userRepository.findById("user-bob")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.storeRecoveryPackage("user-bob", "enc-pkg", "salt", 3, 65536, "127.0.0.1"))
                .isInstanceOf(EmailNotVerifiedException.class);
    }

    @Test
    @DisplayName("Retrieves recovery package for user")
    void retrievesRecoveryPackage() {
        when(featureProperties.isE2eEnabled()).thenReturn(true);

        UserRecoveryPackage pkg = UserRecoveryPackage.create(
                "user-alice", "enc-pkg-data", "salt-123", "ARGON2ID", 3, 65536);
        when(recoveryPackageRepository.findByUserId("user-alice")).thenReturn(Optional.of(pkg));

        RecoveryPackageDto dto = service.getRecoveryPackage("user-alice");

        assertThat(dto).isNotNull();
        assertThat(dto.userId()).isEqualTo("user-alice");
        assertThat(dto.encryptedPackageB64()).isEqualTo("enc-pkg-data");
        assertThat(dto.kdfSaltB64()).isEqualTo("salt-123");
        assertThat(dto.kdfAlgorithm()).isEqualTo("ARGON2ID");
        assertThat(dto.kdfIterations()).isEqualTo(3);
        assertThat(dto.kdfMemoryKib()).isEqualTo(65536);
    }
}

