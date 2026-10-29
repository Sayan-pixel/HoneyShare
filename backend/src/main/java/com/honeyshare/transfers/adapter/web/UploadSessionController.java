package com.honeyshare.transfers.adapter.web;

import com.honeyshare.security.AuthenticatedPrincipal;
import com.honeyshare.security.SecurityContext;
import com.honeyshare.transfers.domain.FileChunk;
import com.honeyshare.transfers.domain.UploadSession;
import com.honeyshare.transfers.service.UploadSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class UploadSessionController {

    private final UploadSessionService uploadSessionService;

    public UploadSessionController(UploadSessionService uploadSessionService) {
        this.uploadSessionService = uploadSessionService;
    }

    @PostMapping({"/transfers/{transferId}/upload-sessions", "/upload-sessions/transfers/{transferId}"})
    public ResponseEntity<Map<String, Object>> createSession(
            @PathVariable String transferId,
            HttpServletRequest request
    ) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        UploadSession session = uploadSessionService.createUploadSession(
                transferId, principal.userId(), ipOf(request));

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "sessionId", session.getId(),
                "transferId", session.getTransferId(),
                "chunkSizeBytes", session.getChunkSizeBytes(),
                "expectedChunkCount", session.getExpectedChunkCount(),
                "state", session.getState().name()
        ));
    }

    @GetMapping("/upload-sessions/{sessionId}/manifest")
    public ResponseEntity<UploadSessionService.UploadManifest> getManifest(@PathVariable String sessionId) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        var manifest = uploadSessionService.getManifest(sessionId, principal.userId());
        return ResponseEntity.ok(manifest);
    }

    @PostMapping("/upload-sessions/{sessionId}/parts/{partIndex}/authorize")
    public ResponseEntity<UploadSessionService.PartUploadAuthorization> authorizePart(
            @PathVariable String sessionId,
            @PathVariable int partIndex
    ) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        var auth = uploadSessionService.authorizePart(sessionId, partIndex, principal.userId());
        return ResponseEntity.ok(auth);
    }

    @PutMapping("/upload-sessions/{sessionId}/parts/{partIndex}")
    public ResponseEntity<Map<String, Object>> uploadPartDirect(
            @PathVariable String sessionId,
            @PathVariable int partIndex,
            InputStream bodyStream,
            @RequestHeader(value = "Content-Length", required = false) Long contentLength
    ) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        long length = (contentLength != null) ? contentLength : 0L;
        FileChunk chunk = uploadSessionService.uploadAndCommitPart(
                sessionId, partIndex, bodyStream, length, principal.userId());

        return ResponseEntity.ok(Map.of(
                "chunkIndex", chunk.getChunkIndex(),
                "state", chunk.getState().name(),
                "storagePartId", chunk.getStoragePartId()
        ));
    }

    @PostMapping("/upload-sessions/{sessionId}/parts/{partIndex}/commit")
    public ResponseEntity<Map<String, Object>> commitPart(
            @PathVariable String sessionId,
            @PathVariable int partIndex,
            @Valid @RequestBody CommitPartRequest body
    ) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        FileChunk chunk = uploadSessionService.commitPart(
                sessionId, partIndex, body.partEtag(), body.checksumValue(), principal.userId());

        return ResponseEntity.ok(Map.of(
                "chunkIndex", chunk.getChunkIndex(),
                "state", chunk.getState().name(),
                "storagePartId", chunk.getStoragePartId()
        ));
    }

    @PostMapping("/upload-sessions/{sessionId}/complete")
    public ResponseEntity<UploadSessionService.DurableStorageReceipt> completeSession(
            @PathVariable String sessionId,
            HttpServletRequest request
    ) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        var receipt = uploadSessionService.completeUploadSession(sessionId, principal.userId(), ipOf(request));
        return ResponseEntity.ok(receipt);
    }

    public record CommitPartRequest(
            @NotBlank String partEtag,
            String checksumValue
    ) {}

    private String ipOf(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return (forwarded != null) ? forwarded.split(",")[0].strip() : request.getRemoteAddr();
    }
}

