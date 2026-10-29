package com.honeyshare.transfers.service;

import com.honeyshare.audit.AuditService;
import com.honeyshare.common.SecureTokens;
import com.honeyshare.common.exception.E2eFeatureDisabledException;
import com.honeyshare.common.exception.EmailNotVerifiedException;
import com.honeyshare.common.exception.RecipientNotAuthorizedException;
import com.honeyshare.common.exception.TransferNotFoundException;
import com.honeyshare.config.FeatureProperties;
import com.honeyshare.downloads.port.DownloadSessionRepository;
import com.honeyshare.identity.domain.User;
import com.honeyshare.identity.port.UserRepository;
import com.honeyshare.notifications.domain.NotificationEventType;
import com.honeyshare.notifications.service.OutboxService;
import com.honeyshare.quotas.QuotaService;
import com.honeyshare.transfers.domain.PrivacyMode;
import com.honeyshare.transfers.domain.RecipientState;
import com.honeyshare.transfers.domain.Transfer;
import com.honeyshare.transfers.domain.TransferRecipient;
import com.honeyshare.transfers.domain.TransferState;
import com.honeyshare.transfers.port.TransferRecipientRepository;
import com.honeyshare.transfers.port.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferRepository transferRepository;
    private final TransferRecipientRepository recipientRepository;
    private final UserRepository userRepository;
    private final QuotaService quotaService;
    private final AuditService auditService;
    private final DownloadSessionRepository downloadSessionRepository;
    private final OutboxService outboxService;
    private final FeatureProperties featureProperties;

    public TransferService(
            TransferRepository transferRepository,
            TransferRecipientRepository recipientRepository,
            UserRepository userRepository,
            QuotaService quotaService,
            AuditService auditService,
            DownloadSessionRepository downloadSessionRepository,
            OutboxService outboxService,
            FeatureProperties featureProperties
    ) {
        this.transferRepository = transferRepository;
        this.recipientRepository = recipientRepository;
        this.userRepository = userRepository;
        this.quotaService = quotaService;
        this.auditService = auditService;
        this.downloadSessionRepository = downloadSessionRepository;
        this.outboxService = outboxService;
        this.featureProperties = featureProperties;
    }

    /**
     * Create a new transfer and associate registered recipients.
     */
    @Transactional
    public Transfer createTransfer(
            String senderUserId,
            String originalFilename,
            long fileSizeBytes,
            String contentTypeHint,
            PrivacyMode privacyMode,
            String expectedSha256,
            List<String> recipientEmails,
            String ipAddress
    ) {
        if (privacyMode == PrivacyMode.E2E && !featureProperties.isE2eEnabled()) {
            throw new E2eFeatureDisabledException();
        }

        User sender = userRepository.findById(senderUserId)
                .orElseThrow(() -> new IllegalArgumentException("Sender user not found"));

        if (!sender.canAccessFiles()) {
            throw new EmailNotVerifiedException();
        }

        if (recipientEmails == null || recipientEmails.isEmpty()) {
            throw new IllegalArgumentException("At least one recipient email is required");
        }

        // Validate recipients exist
        List<User> recipients = new ArrayList<>();
        for (String rawEmail : recipientEmails) {
            String normalized = rawEmail.strip().toLowerCase();
            User recipient = userRepository.findByEmailNormalized(normalized)
                    .orElseThrow(() -> new IllegalArgumentException("Recipient not found: " + normalized));
            recipients.add(recipient);
        }

        // 1. Create transfer record
        Transfer transfer = Transfer.create(
                senderUserId, originalFilename, fileSizeBytes, contentTypeHint, privacyMode, expectedSha256);
        transferRepository.save(transfer);

        // 2. Reserve quota atomically
        quotaService.reserveQuota(senderUserId, transfer.getId(), fileSizeBytes);

        // 3. Create recipient entries
        for (User r : recipients) {
            TransferRecipient tr = TransferRecipient.create(transfer.getId(), r.getId(), r.isEmailVerified());
            recipientRepository.save(tr);
        }

        log.info("Transfer created: id={} senderId={} bytes={} recipients={}",
                transfer.getId(), senderUserId, fileSizeBytes, recipients.size());
        auditService.record(senderUserId, "TRANSFER_CREATED", "transfer", transfer.getId(), ipAddress);

        return transfer;
    }

    /**
     * Authorize and retrieve a transfer by ID.
     * Allowed only for the sender or an assigned recipient.
     */
    @Transactional(readOnly = true)
    public Transfer getAuthorizedTransfer(String transferId, String requestingUserId) {
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));

        boolean isSender = transfer.getSenderId().equals(requestingUserId);
        boolean isRecipient = recipientRepository.existsByTransferIdAndRecipientUserId(transferId, requestingUserId);

        if (!isSender && !isRecipient) {
            throw new RecipientNotAuthorizedException("Not authorized to access transfer: " + transferId);
        }

        return transfer;
    }

    /**
     * Retrieve recipient delivery statuses for a transfer.
     */
    @Transactional(readOnly = true)
    public List<TransferRecipient> getRecipients(String transferId, String requestingUserId) {
        // Enforce authorization
        getAuthorizedTransfer(transferId, requestingUserId);
        return recipientRepository.findByTransferId(transferId);
    }

    /**
     * Cancel an in-flight or stored transfer (sender only).
     */
    @Transactional
    public void cancelTransfer(String transferId, String requestingUserId, String ipAddress) {
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));

        if (!transfer.getSenderId().equals(requestingUserId)) {
            throw new RecipientNotAuthorizedException("Only the sender may cancel the transfer");
        }

        transfer.cancel();
        transferRepository.save(transfer);

        // Release quota reservation if upload was not committed
        quotaService.releaseReservation(transferId);

        // Update recipients to CANCELLED and publish outbox notification
        List<TransferRecipient> recipients = recipientRepository.findByTransferId(transferId);
        for (TransferRecipient r : recipients) {
            r.markCancelled();
            recipientRepository.save(r);

            String eventId = "evt:transfer-cancelled:" + transferId + ":" + r.getRecipientUserId();
            outboxService.publishEvent(
                    r.getRecipientUserId(),
                    NotificationEventType.TRANSFER_CANCELLED,
                    eventId,
                    "Transfer cancelled",
                    "A file transfer shared with you was cancelled by the sender.",
                    "A pending transfer was cancelled.",
                    transferId
            );
        }

        log.info("Transfer cancelled: id={} by sender={}", transferId, requestingUserId);
        auditService.record(requestingUserId, "TRANSFER_CANCELLED", "transfer", transferId, ipAddress);
    }

    /**
     * Dynamically add an eligible registered recipient to an existing transfer.
     */
    @Transactional
    public TransferRecipient addRecipient(String transferId, String senderUserId, String recipientEmail, String ipAddress) {
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));

        if (!transfer.getSenderId().equals(senderUserId)) {
            throw new RecipientNotAuthorizedException("Only the sender may add recipients to the transfer");
        }

        if (transfer.getState().isTerminal()) {
            throw new IllegalStateException("Cannot add recipient to transfer in terminal state: " + transfer.getState());
        }

        String normalizedEmail = recipientEmail.strip().toLowerCase();
        User recipientUser = userRepository.findByEmailNormalized(normalizedEmail)
                .orElseThrow(() -> new IllegalArgumentException("Recipient user not found: " + normalizedEmail));

        if (recipientRepository.existsByTransferIdAndRecipientUserId(transferId, recipientUser.getId())) {
            throw new IllegalArgumentException("Recipient is already registered for this transfer: " + normalizedEmail);
        }

        TransferRecipient tr = TransferRecipient.create(transferId, recipientUser.getId(), recipientUser.isEmailVerified());
        if (transfer.getState() == TransferState.STORED_AND_VERIFIED || transfer.getState() == TransferState.AVAILABLE_TO_RECIPIENT) {
            if (recipientUser.isEmailVerified()) {
                tr.markAvailable(Instant.now());
            }
        }
        recipientRepository.save(tr);

        log.info("Recipient added: transferId={} recipientId={}", transferId, recipientUser.getId());
        auditService.record(senderUserId, "RECIPIENT_ADDED", "transfer", transferId, ipAddress);
        return tr;
    }

    /**
     * Revoke access for a specific recipient (sender only).
     */
    @Transactional
    public void revokeRecipient(String transferId, String senderUserId, String recipientUserId, String ipAddress) {
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));

        if (!transfer.getSenderId().equals(senderUserId)) {
            throw new RecipientNotAuthorizedException("Only the sender may revoke recipients");
        }

        TransferRecipient recipient = recipientRepository.findByTransferIdAndRecipientUserId(transferId, recipientUserId)
                .orElseThrow(() -> new IllegalArgumentException("Recipient not found for transfer: " + recipientUserId));

        recipient.markCancelled();
        recipientRepository.save(recipient);

        // Terminate any active download sessions for this recipient
        downloadSessionRepository.findFirstByTransferIdAndRecipientUserIdAndStateInOrderByCreatedAtDesc(
                transferId, recipientUserId, List.of(com.honeyshare.downloads.domain.DownloadSessionState.AUTHORIZED, com.honeyshare.downloads.domain.DownloadSessionState.IN_PROGRESS)
        ).ifPresent(session -> {
            session.markAbandoned();
            downloadSessionRepository.save(session);
        });

        log.info("Recipient revoked: transferId={} recipientId={}", transferId, recipientUserId);
        auditService.record(senderUserId, "RECIPIENT_REVOKED", "transfer", transferId, ipAddress);
    }

    /**
     * Returns a durable remote storage receipt verifying that the file content
     * has been confirmed and verified in remote object storage.
     * Only after receiving this receipt may the sender client safely delete the local copy.
     */
    @Transactional(readOnly = true)
    public SenderDeletionReceipt getSenderDeletionReceipt(String transferId, String senderUserId) {
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));

        if (!transfer.getSenderId().equals(senderUserId)) {
            throw new RecipientNotAuthorizedException("Only the sender may obtain a durable storage receipt");
        }

        if (transfer.getState() != TransferState.STORED_AND_VERIFIED
                && transfer.getState() != TransferState.AVAILABLE_TO_RECIPIENT
                && transfer.getState() != TransferState.PARTIALLY_DELIVERED
                && transfer.getState() != TransferState.DELIVERED) {
            throw new IllegalStateException("Transfer is not in a durably stored state: " + transfer.getState());
        }

        String payload = transfer.getId() + ":" + transfer.getStorageObjectKey() + ":" +
                transfer.getVerifiedSha256() + ":" + transfer.getStoredAt() + ":" + transfer.getStateVersion();
        String receiptToken = SecureTokens.hash(payload);

        return new SenderDeletionReceipt(
                transfer.getId(),
                transfer.getOriginalFilename(),
                transfer.getFileSizeBytes(),
                transfer.getVerifiedSha256(),
                transfer.getStorageObjectKey(),
                transfer.getStoredAt(),
                receiptToken,
                true,
                "Remote durable storage verified and acknowledged. Remote content will expire after retention policy. " +
                "Note: A remote receipt does not protect against regional storage disasters in single-region deployments."
        );
    }

    public record SenderDeletionReceipt(
            String transferId,
            String originalFilename,
            long fileSizeBytes,
            String verifiedSha256,
            String storageObjectKey,
            Instant storedAt,
            String receiptToken,
            boolean canDeleteLocalSource,
            String durabilityStatement
    ) {}
}

