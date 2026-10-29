package com.honeyshare.downloads.adapter.web;

import com.honeyshare.downloads.service.DownloadService;
import com.honeyshare.security.AuthenticatedPrincipal;
import com.honeyshare.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class DownloadController {

    private final DownloadService downloadService;

    public DownloadController(DownloadService downloadService) {
        this.downloadService = downloadService;
    }

    /**
     * Recipient Inbox: Lists all incoming transfers assigned to the authenticated user.
     */
    @GetMapping("/inbox")
    public ResponseEntity<List<DownloadService.InboxItem>> getInbox() {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        var inbox = downloadService.getRecipientInbox(principal.userId());
        return ResponseEntity.ok(inbox);
    }

    /**
     * Recipient Inbox: Details for a specific incoming transfer.
     */
    @GetMapping("/inbox/{transferId}")
    public ResponseEntity<DownloadService.InboxItem> getInboxItem(@PathVariable String transferId) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        var item = downloadService.getInboxItem(transferId, principal.userId());
        return ResponseEntity.ok(item);
    }

    /**
     * Authorize a download session and issue a short-lived pre-signed URL.
     * Complies with Specification Table 4.2: POST /api/v1/transfers/{id}/download-sessions
     */
    @PostMapping("/transfers/{transferId}/download-sessions")
    public ResponseEntity<DownloadService.DownloadAuthorization> createDownloadSession(
            @PathVariable String transferId,
            HttpServletRequest request
    ) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        var auth = downloadService.authorizeDownload(transferId, principal.userId(), ipOf(request));
        return ResponseEntity.ok(auth);
    }

    /**
     * Directly stream the download payload to the client with full RFC 7233 byte range support.
     * Guarantees same-origin delivery without relying on external S3 CORS configuration.
     */
    @GetMapping("/download-sessions/{sessionId}/stream")
    public ResponseEntity<InputStreamResource> streamDownload(
            @PathVariable String sessionId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader
    ) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            var fullPayload = downloadService.openDownloadStream(sessionId, principal.userId());
            long totalSize = fullPayload.sizeBytes();

            String rangeValue = rangeHeader.substring(6).trim();
            String[] parts = rangeValue.split("-", -1);
            long start = parts.length > 0 && !parts[0].isBlank() ? Long.parseLong(parts[0].trim()) : 0;
            Long requestedEnd = parts.length > 1 && !parts[1].isBlank() ? Long.parseLong(parts[1].trim()) : null;
            long end = requestedEnd != null ? Math.min(requestedEnd, totalSize - 1) : (totalSize > 0 ? totalSize - 1 : 0);

            var rangePayload = downloadService.openDownloadRangeStream(sessionId, principal.userId(), start, end);
            long rangeLength = rangePayload.sizeBytes();

            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + totalSize)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + rangePayload.filename().replace("\"", "") + "\"")
                    .header(HttpHeaders.CONTENT_TYPE, rangePayload.contentType())
                    .contentLength(rangeLength)
                    .body(new InputStreamResource(rangePayload.stream()));
        }

        var payload = downloadService.openDownloadStream(sessionId, principal.userId());
        return ResponseEntity.ok()
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + payload.filename().replace("\"", "") + "\"")
                .header(HttpHeaders.CONTENT_TYPE, payload.contentType())
                .contentLength(payload.sizeBytes())
                .body(new InputStreamResource(payload.stream()));
    }

    /**
     * Legacy/direct route for download authorization.
     */
    @PostMapping("/downloads/transfers/{transferId}/authorize")
    public ResponseEntity<DownloadService.DownloadAuthorization> authorizeDownload(
            @PathVariable String transferId,
            HttpServletRequest request
    ) {
        return createDownloadSession(transferId, request);
    }

    /**
     * Acknowledge verified download completion for a session.
     * Complies with Specification Table 4.2: POST /api/v1/download-sessions/{id}/complete
     */
    @PostMapping("/download-sessions/{sessionId}/complete")
    public ResponseEntity<Map<String, String>> completeDownloadSession(
            @PathVariable String sessionId,
            @RequestBody(required = false) AcknowledgeRequest body,
            HttpServletRequest request
    ) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        String checksum = (body != null) ? body.verifiedChecksum() : null;
        downloadService.completeDownloadSession(sessionId, principal.userId(), checksum, ipOf(request));
        return ResponseEntity.ok(Map.of("message", "Download completed and verified"));
    }

    /**
     * Cancel an active download session.
     * Complies with Specification Table 4.2: POST /api/v1/download-sessions/{id}/cancel
     */
    @PostMapping("/download-sessions/{sessionId}/cancel")
    public ResponseEntity<Map<String, String>> cancelDownloadSession(@PathVariable String sessionId) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        downloadService.cancelDownloadSession(sessionId, principal.userId());
        return ResponseEntity.ok(Map.of("message", "Download session cancelled"));
    }

    /**
     * Legacy route for download acknowledgement by transferId.
     */
    @PostMapping("/downloads/transfers/{transferId}/acknowledge")
    public ResponseEntity<Map<String, String>> acknowledgeDownload(
            @PathVariable String transferId,
            @RequestBody(required = false) AcknowledgeRequest body,
            HttpServletRequest request
    ) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        String checksum = (body != null) ? body.verifiedChecksum() : null;
        downloadService.acknowledgeDownload(transferId, principal.userId(), checksum, ipOf(request));
        return ResponseEntity.ok(Map.of("message", "Download acknowledged"));
    }

    public record AcknowledgeRequest(String verifiedChecksum) {}

    private String ipOf(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return (forwarded != null) ? forwarded.split(",")[0].strip() : request.getRemoteAddr();
    }
}
