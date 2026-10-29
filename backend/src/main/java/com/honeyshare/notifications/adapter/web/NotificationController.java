package com.honeyshare.notifications.adapter.web;

import com.honeyshare.notifications.service.InboxNotificationService;
import com.honeyshare.security.AuthenticatedPrincipal;
import com.honeyshare.security.SecurityContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class NotificationController {

    private final InboxNotificationService notificationService;

    public NotificationController(InboxNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/notifications")
    public ResponseEntity<List<InboxNotificationService.NotificationDto>> getNotifications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        var list = notificationService.getNotifications(principal.userId(), page, size);
        return ResponseEntity.ok(list);
    }

    @PostMapping("/notifications/{id}/read")
    public ResponseEntity<Map<String, String>> markRead(@PathVariable String id) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        notificationService.markRead(id, principal.userId());
        return ResponseEntity.ok(Map.of("message", "Notification marked as read"));
    }

    @PostMapping("/notifications/read-all")
    public ResponseEntity<Map<String, String>> markAllRead() {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        notificationService.markAllRead(principal.userId());
        return ResponseEntity.ok(Map.of("message", "All notifications marked as read"));
    }

    @GetMapping("/notifications/unread-count")
    public ResponseEntity<Map<String, Long>> getUnreadCount() {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        long count = notificationService.getUnreadCount(principal.userId());
        return ResponseEntity.ok(Map.of("unreadCount", count));
    }

    @GetMapping("/users/me/preferences")
    public ResponseEntity<InboxNotificationService.PreferencesDto> getPreferences() {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        var prefs = notificationService.getPreferences(principal.userId());
        return ResponseEntity.ok(prefs);
    }

    @PatchMapping("/users/me/preferences")
    public ResponseEntity<InboxNotificationService.PreferencesDto> updatePreferences(
            @RequestBody InboxNotificationService.UpdatePreferencesRequest body
    ) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        var prefs = notificationService.updatePreferences(principal.userId(), body);
        return ResponseEntity.ok(prefs);
    }
}

