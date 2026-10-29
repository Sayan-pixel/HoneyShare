package com.honeyshare.transfers.adapter.web;

import com.honeyshare.security.AuthenticatedPrincipal;
import com.honeyshare.security.SecurityContext;
import com.honeyshare.transfers.domain.PrivacyMode;
import com.honeyshare.transfers.domain.Transfer;
import com.honeyshare.transfers.domain.TransferRecipient;
import com.honeyshare.transfers.port.TransferRepository;
import com.honeyshare.transfers.service.TransferService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferService transferService;
    private final TransferRepository transferRepository;

    public TransferController(TransferService transferService, TransferRepository transferRepository) {
        this.transferService = transferService;
        this.transferRepository = transferRepository;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createTransfer(
            @Valid @RequestBody CreateTransferRequest body,
            HttpServletRequest request
    ) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();

        Transfer transfer = transferService.createTransfer(
                principal.userId(),
                body.originalFilename(),
                body.fileSizeBytes(),
                body.contentTypeHint(),
                body.privacyMode(),
                body.expectedSha256(),
                body.recipientEmails(),
                ipOf(request)
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "transferId", transfer.getId(),
                "state", transfer.getState().name(),
                "originalFilename", transfer.getOriginalFilename(),
                "fileSizeBytes", transfer.getFileSizeBytes(),
                "storageObjectKey", transfer.getStorageObjectKey(),
                "createdAt", transfer.getCreatedAt()
        ));
    }

    @GetMapping("/sent")
    public ResponseEntity<List<TransferSummaryDto>> getSentTransfers() {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        List<Transfer> transfers = transferRepository.findBySenderIdOrderByCreatedAtDesc(principal.userId());
        List<TransferSummaryDto> dtos = transfers.stream()
                .map(t -> new TransferSummaryDto(
                        t.getId(),
                        t.getOriginalFilename(),
                        t.getFileSizeBytes(),
                        t.getPrivacyMode().name(),
                        t.getState().name(),
                        t.getCreatedAt(),
                        t.getExpiresAt()
                ))
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/{transferId}")
    public ResponseEntity<Map<String, Object>> getTransfer(@PathVariable String transferId) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        Transfer t = transferService.getAuthorizedTransfer(transferId, principal.userId());

        return ResponseEntity.ok(Map.of(
                "transferId", t.getId(),
                "senderId", t.getSenderId(),
                "originalFilename", t.getOriginalFilename(),
                "fileSizeBytes", t.getFileSizeBytes(),
                "privacyMode", t.getPrivacyMode().name(),
                "state", t.getState().name(),
                "expectedSha256", t.getExpectedSha256(),
                "verifiedSha256", t.getVerifiedSha256() != null ? t.getVerifiedSha256() : "",
                "createdAt", t.getCreatedAt(),
                "expiresAt", t.getExpiresAt() != null ? t.getExpiresAt() : ""
        ));
    }

    @GetMapping("/{transferId}/recipients")
    public ResponseEntity<List<RecipientDto>> getRecipients(@PathVariable String transferId) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        List<TransferRecipient> list = transferService.getRecipients(transferId, principal.userId());

        List<RecipientDto> dtos = list.stream()
                .map(r -> new RecipientDto(
                        r.getRecipientUserId(), r.getState().name(), r.getAvailableAt(), r.getVerifiedAt()))
                .toList();

        return ResponseEntity.ok(dtos);
    }

    @PostMapping("/{transferId}/cancel")
    public ResponseEntity<Map<String, String>> cancelTransfer(
            @PathVariable String transferId,
            HttpServletRequest request
    ) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        transferService.cancelTransfer(transferId, principal.userId(), ipOf(request));
        return ResponseEntity.ok(Map.of("message", "Transfer cancelled"));
    }

    @PostMapping("/{transferId}/recipients")
    public ResponseEntity<RecipientDto> addRecipient(
            @PathVariable String transferId,
            @Valid @RequestBody AddRecipientRequest body,
            HttpServletRequest request
    ) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        TransferRecipient tr = transferService.addRecipient(
                transferId, principal.userId(), body.recipientEmail(), ipOf(request));

        return ResponseEntity.status(HttpStatus.CREATED).body(new RecipientDto(
                tr.getRecipientUserId(), tr.getState().name(), tr.getAvailableAt(), tr.getVerifiedAt()));
    }

    @DeleteMapping("/{transferId}/recipients/{recipientUserId}")
    public ResponseEntity<Map<String, String>> revokeRecipient(
            @PathVariable String transferId,
            @PathVariable String recipientUserId,
            HttpServletRequest request
    ) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        transferService.revokeRecipient(transferId, principal.userId(), recipientUserId, ipOf(request));
        return ResponseEntity.ok(Map.of("message", "Recipient revoked"));
    }

    @PostMapping("/{transferId}/sender-deletion-receipt")
    public ResponseEntity<TransferService.SenderDeletionReceipt> getSenderDeletionReceipt(
            @PathVariable String transferId
    ) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        TransferService.SenderDeletionReceipt receipt =
                transferService.getSenderDeletionReceipt(transferId, principal.userId());
        return ResponseEntity.ok(receipt);
    }

    public record AddRecipientRequest(@NotBlank String recipientEmail) {}

    public record CreateTransferRequest(
            @NotBlank String originalFilename,
            @Min(0) long fileSizeBytes,
            String contentTypeHint,
            PrivacyMode privacyMode,
            @NotBlank String expectedSha256,
            @NotEmpty List<String> recipientEmails
    ) {}

    public record RecipientDto(
            String recipientUserId, String state, Object availableAt, Object verifiedAt
    ) {}

    public record TransferSummaryDto(
            String transferId,
            String originalFilename,
            long fileSizeBytes,
            String privacyMode,
            String state,
            java.time.Instant createdAt,
            java.time.Instant expiresAt
    ) {}

    private String ipOf(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return (forwarded != null) ? forwarded.split(",")[0].strip() : request.getRemoteAddr();
    }
}

