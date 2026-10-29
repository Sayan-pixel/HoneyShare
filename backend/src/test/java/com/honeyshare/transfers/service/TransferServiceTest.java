package com.honeyshare.transfers.service;

import com.honeyshare.audit.AuditService;
import com.honeyshare.common.exception.EmailNotVerifiedException;
import com.honeyshare.common.exception.RecipientNotAuthorizedException;
import com.honeyshare.common.exception.TransferNotFoundException;
import com.honeyshare.downloads.port.DownloadSessionRepository;
import com.honeyshare.identity.domain.User;
import com.honeyshare.identity.domain.UserRole;
import com.honeyshare.identity.port.UserRepository;
import com.honeyshare.quotas.QuotaService;
import com.honeyshare.transfers.domain.PrivacyMode;
import com.honeyshare.transfers.domain.RecipientState;
import com.honeyshare.transfers.domain.Transfer;
import com.honeyshare.transfers.domain.TransferRecipient;
import com.honeyshare.transfers.domain.TransferState;
import com.honeyshare.transfers.port.TransferRecipientRepository;
import com.honeyshare.transfers.port.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
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
class TransferServiceTest {

    @Mock TransferRepository transferRepository;
    @Mock TransferRecipientRepository recipientRepository;
    @Mock UserRepository userRepository;
    @Mock QuotaService quotaService;
    @Mock AuditService auditService;
    @Mock DownloadSessionRepository downloadSessionRepository;
    @Mock com.honeyshare.notifications.service.OutboxService outboxService;
    @Mock com.honeyshare.config.FeatureProperties featureProperties;

    TransferService transferService;

    @BeforeEach
    void setUp() {
        transferService = new TransferService(
                transferRepository, recipientRepository, userRepository, quotaService, auditService, downloadSessionRepository, outboxService, featureProperties);
    }

    @Test
    void createTransfer_withVerifiedSenderAndValidRecipient_createsRecordsAndReservesQuota() {
        User sender = User.create("sender@example.com", "hash", UserRole.USER);
        sender.verifyEmail(Instant.now());

        User recipient = User.create("recipient@example.com", "hash", UserRole.USER);
        recipient.verifyEmail(Instant.now());

        when(userRepository.findById(sender.getId())).thenReturn(Optional.of(sender));
        when(userRepository.findByEmailNormalized("recipient@example.com")).thenReturn(Optional.of(recipient));
        when(transferRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Transfer t = transferService.createTransfer(
                sender.getId(), "document.pdf", 1024L, "application/pdf",
                PrivacyMode.STANDARD, "hash123", List.of("recipient@example.com"), "127.0.0.1"
        );

        assertThat(t).isNotNull();
        assertThat(t.getState()).isEqualTo(TransferState.CREATED);
        assertThat(t.getOriginalFilename()).isEqualTo("document.pdf");
        assertThat(t.getFileSizeBytes()).isEqualTo(1024L);

        verify(quotaService).reserveQuota(sender.getId(), t.getId(), 1024L);
        verify(recipientRepository).save(any());
        verify(auditService).record(eq(sender.getId()), eq("TRANSFER_CREATED"), eq("transfer"), eq(t.getId()), eq("127.0.0.1"));
    }

    @Test
    void createTransfer_unverifiedSender_throwsEmailNotVerifiedException() {
        User sender = User.create("sender@example.com", "hash", UserRole.USER);
        // sender email is NOT verified

        when(userRepository.findById(sender.getId())).thenReturn(Optional.of(sender));

        assertThatThrownBy(() -> transferService.createTransfer(
                sender.getId(), "document.pdf", 1024L, null,
                PrivacyMode.STANDARD, "hash123", List.of("recipient@example.com"), "127.0.0.1"))
                .isInstanceOf(EmailNotVerifiedException.class);

        verify(quotaService, never()).reserveQuota(any(), any(), anyLong());
    }

    @Test
    void getAuthorizedTransfer_allowsSenderOrRecipientOnly() {
        Transfer t = Transfer.create("sender-1", "file.txt", 100, null, PrivacyMode.STANDARD, "hash");
        when(transferRepository.findById(t.getId())).thenReturn(Optional.of(t));

        // Sender access allowed
        assertThat(transferService.getAuthorizedTransfer(t.getId(), "sender-1")).isEqualTo(t);

        // Recipient access allowed
        when(recipientRepository.existsByTransferIdAndRecipientUserId(t.getId(), "recipient-1")).thenReturn(true);
        assertThat(transferService.getAuthorizedTransfer(t.getId(), "recipient-1")).isEqualTo(t);

        // Unauthorized user throws
        when(recipientRepository.existsByTransferIdAndRecipientUserId(t.getId(), "stranger")).thenReturn(false);
        assertThatThrownBy(() -> transferService.getAuthorizedTransfer(t.getId(), "stranger"))
                .isInstanceOf(RecipientNotAuthorizedException.class);
    }

    @Test
    void cancelTransfer_releasesQuotaAndMarksRecipientsCancelled() {
        Transfer t = Transfer.create("sender-1", "file.txt", 100, null, PrivacyMode.STANDARD, "hash");
        TransferRecipient tr = TransferRecipient.create(t.getId(), "recipient-1", true);

        when(transferRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(recipientRepository.findByTransferId(t.getId())).thenReturn(List.of(tr));

        transferService.cancelTransfer(t.getId(), "sender-1", "127.0.0.1");

        assertThat(t.getState()).isEqualTo(TransferState.CANCELLED);
        assertThat(tr.getState()).isEqualTo(RecipientState.CANCELLED);
        verify(quotaService).releaseReservation(t.getId());
        verify(auditService).record(eq("sender-1"), eq("TRANSFER_CANCELLED"), eq("transfer"), eq(t.getId()), eq("127.0.0.1"));
    }

    @Test
    void cancelTransfer_byNonSender_throwsRecipientNotAuthorizedException() {
        Transfer t = Transfer.create("sender-1", "file.txt", 100, null, PrivacyMode.STANDARD, "hash");
        when(transferRepository.findById(t.getId())).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> transferService.cancelTransfer(t.getId(), "other-user", "127.0.0.1"))
                .isInstanceOf(RecipientNotAuthorizedException.class);

        assertThat(t.getState()).isEqualTo(TransferState.CREATED);
    }

    @Test
    void addRecipient_senderCanAddEligibleRecipient() {
        Transfer t = Transfer.create("sender-1", "file.txt", 100, null, PrivacyMode.STANDARD, "hash");
        User newRecipient = User.create("friend@example.com", "hash", UserRole.USER);
        newRecipient.verifyEmail(Instant.now());

        when(transferRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(userRepository.findByEmailNormalized("friend@example.com")).thenReturn(Optional.of(newRecipient));
        when(recipientRepository.existsByTransferIdAndRecipientUserId(t.getId(), newRecipient.getId())).thenReturn(false);

        TransferRecipient tr = transferService.addRecipient(t.getId(), "sender-1", "friend@example.com", "127.0.0.1");

        assertThat(tr.getRecipientUserId()).isEqualTo(newRecipient.getId());
        verify(recipientRepository).save(tr);
        verify(auditService).record(eq("sender-1"), eq("RECIPIENT_ADDED"), eq("transfer"), eq(t.getId()), eq("127.0.0.1"));
    }

    @Test
    void revokeRecipient_senderCanRevokeAccess() {
        Transfer t = Transfer.create("sender-1", "file.txt", 100, null, PrivacyMode.STANDARD, "hash");
        TransferRecipient tr = TransferRecipient.create(t.getId(), "recipient-1", true);

        when(transferRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(recipientRepository.findByTransferIdAndRecipientUserId(t.getId(), "recipient-1")).thenReturn(Optional.of(tr));
        when(downloadSessionRepository.findFirstByTransferIdAndRecipientUserIdAndStateInOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());

        transferService.revokeRecipient(t.getId(), "sender-1", "recipient-1", "127.0.0.1");

        assertThat(tr.getState()).isEqualTo(RecipientState.CANCELLED);
        verify(recipientRepository).save(tr);
        verify(auditService).record(eq("sender-1"), eq("RECIPIENT_REVOKED"), eq("transfer"), eq(t.getId()), eq("127.0.0.1"));
    }

    @Test
    void getSenderDeletionReceipt_storedTransfer_returnsValidReceipt() {
        Transfer t = Transfer.create("sender-1", "file.txt", 100, null, PrivacyMode.STANDARD, "hash");
        t.markUploading();
        t.markVerifying();
        t.markStoredAndVerified("hash", Instant.now(), Instant.now().plusSeconds(86400));
        t.makeAvailableToRecipient();

        when(transferRepository.findById(t.getId())).thenReturn(Optional.of(t));

        var receipt = transferService.getSenderDeletionReceipt(t.getId(), "sender-1");

        assertThat(receipt.transferId()).isEqualTo(t.getId());
        assertThat(receipt.canDeleteLocalSource()).isTrue();
        assertThat(receipt.receiptToken()).isNotBlank();
        assertThat(receipt.verifiedSha256()).isEqualTo("hash");
    }

    @Test
    void getSenderDeletionReceipt_unverifiedTransfer_throwsIllegalStateException() {
        Transfer t = Transfer.create("sender-1", "file.txt", 100, null, PrivacyMode.STANDARD, "hash");
        when(transferRepository.findById(t.getId())).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> transferService.getSenderDeletionReceipt(t.getId(), "sender-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not in a durably stored state");
    }
}
