package com.honeyshare.encryption.adapter.web;

import com.honeyshare.encryption.service.E2eKeyManagementService;
import com.honeyshare.encryption.service.E2eKeyManagementService.EnvelopeSubmission;
import com.honeyshare.encryption.service.E2eKeyManagementService.KeyEnvelopeDto;
import com.honeyshare.encryption.service.E2eKeyManagementService.RecoveryPackageDto;
import com.honeyshare.security.AuthenticatedPrincipal;
import com.honeyshare.security.SecurityContext;
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
@RequestMapping("/api/v1")
public class E2eKeyController {

    private final E2eKeyManagementService e2eKeyManagementService;

    public E2eKeyController(E2eKeyManagementService e2eKeyManagementService) {
        this.e2eKeyManagementService = e2eKeyManagementService;
    }

    @PostMapping("/transfers/{transferId}/e2e-envelopes")
    public ResponseEntity<Map<String, Object>> storeEnvelopes(
            @PathVariable String transferId,
            @Valid @RequestBody StoreEnvelopesRequest body,
            HttpServletRequest request
    ) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        List<EnvelopeSubmission> submissions = body.envelopes().stream()
                .map(e -> new EnvelopeSubmission(
                        e.recipientDeviceId(),
                        e.ephemeralPublicKeyB64(),
                        e.encryptedKeyB64(),
                        e.keyAlgorithm()
                ))
                .toList();

        e2eKeyManagementService.storeTransferKeyEnvelopes(
                transferId,
                principal.userId(),
                body.senderDeviceId(),
                submissions,
                ipOf(request)
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "transferId", transferId,
                "status", "ENVELOPES_STORED",
                "count", submissions.size()
        ));
    }

    @GetMapping("/transfers/{transferId}/e2e-envelopes/{deviceId}")
    public ResponseEntity<KeyEnvelopeDto> getEnvelope(
            @PathVariable String transferId,
            @PathVariable String deviceId
    ) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        KeyEnvelopeDto dto = e2eKeyManagementService.getTransferKeyEnvelope(transferId, principal.userId(), deviceId);
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/users/me/e2e-recovery")
    public ResponseEntity<Map<String, String>> storeRecoveryPackage(
            @Valid @RequestBody StoreRecoveryPackageRequest body,
            HttpServletRequest request
    ) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        e2eKeyManagementService.storeRecoveryPackage(
                principal.userId(),
                body.encryptedPackageB64(),
                body.kdfSaltB64(),
                body.kdfIterations(),
                body.kdfMemoryKib(),
                ipOf(request)
        );

        return ResponseEntity.ok(Map.of(
                "status", "RECOVERY_PACKAGE_SAVED",
                "userId", principal.userId()
        ));
    }

    @GetMapping("/users/me/e2e-recovery")
    public ResponseEntity<RecoveryPackageDto> getRecoveryPackage() {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        RecoveryPackageDto dto = e2eKeyManagementService.getRecoveryPackage(principal.userId());
        return ResponseEntity.ok(dto);
    }

    private String ipOf(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            return fwd.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public record StoreEnvelopesRequest(
            @NotBlank(message = "Sender device ID is required") String senderDeviceId,
            @NotEmpty(message = "At least one key envelope is required") List<@Valid EnvelopeItemRequest> envelopes
    ) {}

    public record EnvelopeItemRequest(
            @NotBlank(message = "Recipient device ID is required") String recipientDeviceId,
            @NotBlank(message = "Ephemeral public key is required") String ephemeralPublicKeyB64,
            @NotBlank(message = "Encrypted key is required") String encryptedKeyB64,
            String keyAlgorithm
    ) {}

    public record StoreRecoveryPackageRequest(
            @NotBlank(message = "Encrypted recovery package is required") String encryptedPackageB64,
            @NotBlank(message = "KDF salt is required") String kdfSaltB64,
            @Min(value = 1, message = "Iterations must be at least 1") int kdfIterations,
            @Min(value = 1024, message = "Memory must be at least 1024 KiB") int kdfMemoryKib
    ) {}
}

