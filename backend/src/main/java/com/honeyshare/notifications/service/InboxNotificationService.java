package com.honeyshare.notifications.service;

import com.honeyshare.notifications.domain.InboxNotification;
import com.honeyshare.notifications.domain.UserNotificationPreferences;
import com.honeyshare.notifications.port.InboxNotificationRepository;
import com.honeyshare.notifications.port.UserNotificationPreferencesRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class InboxNotificationService {

    private final InboxNotificationRepository inboxNotificationRepository;
    private final UserNotificationPreferencesRepository preferencesRepository;

    public InboxNotificationService(
            InboxNotificationRepository inboxNotificationRepository,
            UserNotificationPreferencesRepository preferencesRepository
    ) {
        this.inboxNotificationRepository = inboxNotificationRepository;
        this.preferencesRepository = preferencesRepository;
    }

    @Transactional(readOnly = true)
    public List<NotificationDto> getNotifications(String userId, int page, int size) {
        int sanitizedPage = Math.max(0, page);
        int sanitizedSize = Math.min(100, Math.max(1, size));

        List<InboxNotification> list = inboxNotificationRepository.findByUserIdOrderByCreatedAtDesc(
                userId, PageRequest.of(sanitizedPage, sanitizedSize));

        return list.stream()
                .map(n -> new NotificationDto(
                        n.getId(), n.getNotificationType(), n.getTransferId(),
                        n.getTitle(), n.getMessage(), n.isRead(), n.getCreatedAt(), n.getReadAt()))
                .toList();
    }

    @Transactional
    public void markRead(String notificationId, String userId) {
        inboxNotificationRepository.findById(notificationId).ifPresent(n -> {
            if (n.getUserId().equals(userId)) {
                n.markRead(Instant.now());
                inboxNotificationRepository.save(n);
            }
        });
    }

    @Transactional
    public void markAllRead(String userId) {
        List<InboxNotification> unread = inboxNotificationRepository.findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(userId);
        Instant now = Instant.now();
        for (InboxNotification n : unread) {
            n.markRead(now);
            inboxNotificationRepository.save(n);
        }
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(String userId) {
        return inboxNotificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public PreferencesDto getPreferences(String userId) {
        UserNotificationPreferences p = preferencesRepository.findByUserId(userId)
                .orElseGet(() -> {
                    UserNotificationPreferences created = UserNotificationPreferences.createDefault(userId);
                    return preferencesRepository.save(created);
                });

        return new PreferencesDto(
                p.isEmailEnabled(),
                p.isPushEnabled(),
                p.isInAppEnabled(),
                p.isNotifyOnTransferReceived(),
                p.isNotifyOnDownloadComplete(),
                p.isNotifyOnTransferExpiring()
        );
    }

    @Transactional
    public PreferencesDto updatePreferences(String userId, UpdatePreferencesRequest req) {
        UserNotificationPreferences p = preferencesRepository.findByUserId(userId)
                .orElseGet(() -> UserNotificationPreferences.createDefault(userId));

        p.update(
                req.emailEnabled(),
                req.pushEnabled(),
                req.inAppEnabled(),
                req.notifyOnTransferReceived(),
                req.notifyOnDownloadComplete(),
                req.notifyOnTransferExpiring()
        );
        preferencesRepository.save(p);

        return new PreferencesDto(
                p.isEmailEnabled(),
                p.isPushEnabled(),
                p.isInAppEnabled(),
                p.isNotifyOnTransferReceived(),
                p.isNotifyOnDownloadComplete(),
                p.isNotifyOnTransferExpiring()
        );
    }

    public record NotificationDto(
            String id,
            String notificationType,
            String transferId,
            String title,
            String message,
            boolean read,
            Instant createdAt,
            Instant readAt
    ) {}

    public record PreferencesDto(
            boolean emailEnabled,
            boolean pushEnabled,
            boolean inAppEnabled,
            boolean notifyOnTransferReceived,
            boolean notifyOnDownloadComplete,
            boolean notifyOnTransferExpiring
    ) {}

    public record UpdatePreferencesRequest(
            Boolean emailEnabled,
            Boolean pushEnabled,
            Boolean inAppEnabled,
            Boolean notifyOnTransferReceived,
            Boolean notifyOnDownloadComplete,
            Boolean notifyOnTransferExpiring
    ) {}
}

