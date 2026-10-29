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
import com.honeyshare.identity.domain.User;
import com.honeyshare.identity.port.UserRepository;
import com.honeyshare.transfers.domain.PrivacyMode;
import com.honeyshare.transfers.domain.Transfer;
import com.honeyshare.transfers.port.TransferRecipientRepository;
import com.honeyshare.transfers.port.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Service managing E2E key envelopes and user-controlled recovery packages.
 * Complies with HoneyShare Specification Section 3.3, Section 5, and Section 12.2.
 *
 * CRITICAL SECURITY INVARIANTS:
 * 1. The server NEVER receives, computes, stores, or logs plaintext file symmetric keys.
 * 2. The server NEVER receives, computes, stores, or logs plaintext device private keys.
 * 3. The server NEVER receives, computes, stores, or logs plaintext recovery secrets.
 * 4. All E2E operations are rejected with 403 when honeyshare.features.e2e-enabled=false.
 */
@Service
public class E2eKeyManagementService {

    private static final Logger log = LoggerFactory.getLogger(E2eKeyManagementService.class);

    private final TransferRepository transferRepository;
    private final TransferRecipientRepository recipientRepository;
    private final DeviceRepository deviceRepository;
    private final TransferKeyEnvelopeRepository envelopeRepository;
    private final UserRecoveryPackageRepository recoveryPackageRepository;
    private final UserRepository userRepository;
    private final FeatureProperties featureProperties;
    private final AuditService auditService;

    public E2eKeyManagementService(
            TransferRepository transferRepository,
            TransferRecipientRepository recipientRepository,
            DeviceRepository deviceRepository,
            TransferKeyEnvelopeRepository envelopeRepository,
            UserRecoveryPackageRepository recoveryPackageRepository,
            UserRepository userRepository,
            FeatureProperties featureProperties,
            AuditService auditService
    ) {
        this.transferRepository = transferRepository;
        this.recipientRepository = recipientRepository;
        this.deviceRepository = deviceRepository;
        this.envelopeRepository = envelopeRepository;
        this.recoveryPackageRepository = recoveryPackageRepository;
        this.userRepository = userRepository;
        this.featureProperties = featureProperties;
        this.auditService = auditService;
    }

    private void assertE2eEnabled() {
        if (!featureProperties.isE2eEnabled()) {
            throw new E2eFeatureDisabledException();
        }
    }

    /**
     * Store encrypted key envelopes for recipient devices.
     */
    @Transactional
    public void storeTransferKeyEnvelopes(
            String transferId,
            String senderUserId,
            String senderDeviceId,
            List<EnvelopeSubmission> submissions,
            String ipAddress
    ) {
        assertE2eEnabled();

        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));

        if (!transfer.getSenderId().equals(senderUserId)) {
            throw new RecipientNotAuthorizedException("Only the sender may store key envelopes");
        }

        if (transfer.getPrivacyMode() != PrivacyMode.E2E) {
            throw new IllegalStateException("Transfer is not in E2E mode");
        }

        for (EnvelopeSubmission sub : submissions) {
            Device recipientDevice = deviceRepository.findById(sub.recipientDeviceId())
                    .orElseThrow(() -> new IllegalArgumentException("Recipient device not found: " + sub.recipientDeviceId()));

            if (recipientDevice.isRevoked()) {
                throw new IllegalStateException("Cannot create envelope for revoked device: " + recipientDevice.getId());
            }

            if (!envelopeRepository.existsByTransferIdAndRecipientDeviceId(transferId, sub.recipientDeviceId())) {
                TransferKeyEnvelope envelope = TransferKeyEnvelope.create(
                        transferId,
                        sub.recipientDeviceId(),
                        senderDeviceId,
                        sub.ephemeralPublicKeyB64(),
                        sub.encryptedKeyB64(),
                        sub.keyAlgorithm()
                );
                envelopeRepository.save(envelope);
            }
        }

        log.info("Stored {} key envelope(s) for transfer {}", submissions.size(), transferId);
        auditService.record(senderUserId, "E2E_KEY_ENVELOPES_STORED", "transfer", transferId, ipAddress);
    }

    /**
     * Retrieve the wrapped key envelope for a recipient's specific device.
     */
    @Transactional(readOnly = true)
    public KeyEnvelopeDto getTransferKeyEnvelope(String transferId, String recipientUserId, String recipientDeviceId) {
        assertE2eEnabled();

        Device device = deviceRepository.findByIdAndUserId(recipientDeviceId, recipientUserId)
                .orElseThrow(() -> new RecipientNotAuthorizedException("Device not found or not owned by recipient: " + recipientDeviceId));

        if (device.isRevoked()) {
            throw new RecipientNotAuthorizedException("Device access has been revoked");
        }

        TransferKeyEnvelope envelope = envelopeRepository.findByTransferIdAndRecipientDeviceId(transferId, recipientDeviceId)
                .orElseThrow(() -> new IllegalArgumentException("No key envelope found for device: " + recipientDeviceId));

        return new KeyEnvelopeDto(
                envelope.getId(),
                envelope.getTransferId(),
                envelope.getRecipientDeviceId(),
                envelope.getSenderDeviceId(),
                envelope.getEphemeralPublicKeyB64(),
                envelope.getEncryptedKeyB64(),
                envelope.getKeyAlgorithm(),
                envelope.getCreatedAt()
        );
    }

    /**
     * Store or update a user-controlled encrypted recovery package.
     */
    @Transactional
    public void storeRecoveryPackage(
            String userId,
            String encryptedPackageB64,
            String kdfSaltB64,
            int kdfIterations,
            int kdfMemoryKib,
            String ipAddress
    ) {
        assertE2eEnabled();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        if (!user.canAccessFiles()) {
            throw new EmailNotVerifiedException();
        }

        UserRecoveryPackage pkg = recoveryPackageRepository.findByUserId(userId)
                .map(existing -> {
                    existing.update(encryptedPackageB64, kdfSaltB64, kdfIterations, kdfMemoryKib);
                    return existing;
                })
                .orElseGet(() -> UserRecoveryPackage.create(
                        userId, encryptedPackageB64, kdfSaltB64, "ARGON2ID", kdfIterations, kdfMemoryKib));

        recoveryPackageRepository.save(pkg);
        log.info("E2E recovery package stored for user: {}", userId);
        auditService.record(userId, "E2E_RECOVERY_PACKAGE_STORED", "user", userId, ipAddress);
    }

    /**
     * Retrieve the user's encrypted recovery package and public KDF parameters.
     */
    @Transactional(readOnly = true)
    public RecoveryPackageDto getRecoveryPackage(String userId) {
        assertE2eEnabled();

        UserRecoveryPackage pkg = recoveryPackageRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("No E2E recovery package registered for user"));

        return new RecoveryPackageDto(
                pkg.getUserId(),
                pkg.getEncryptedPackageB64(),
                pkg.getKdfSaltB64(),
                pkg.getKdfAlgorithm(),
                pkg.getKdfIterations(),
                pkg.getKdfMemoryKib(),
                pkg.getUpdatedAt()
        );
    }

    public record EnvelopeSubmission(
            String recipientDeviceId,
            String ephemeralPublicKeyB64,
            String encryptedKeyB64,
            String keyAlgorithm
    ) {}

    public record KeyEnvelopeDto(
            String id,
            String transferId,
            String recipientDeviceId,
            String senderDeviceId,
            String ephemeralPublicKeyB64,
            String encryptedKeyB64,
            String keyAlgorithm,
            Instant createdAt
    ) {}

    public record RecoveryPackageDto(
            String userId,
            String encryptedPackageB64,
            String kdfSaltB64,
            String kdfAlgorithm,
            int kdfIterations,
            int kdfMemoryKib,
            Instant updatedAt
    ) {}
}

