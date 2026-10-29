package com.honeyshare.notifications.service;

import com.honeyshare.notifications.domain.InboxNotification;
import com.honeyshare.notifications.domain.UserNotificationPreferences;
import com.honeyshare.notifications.port.InboxNotificationRepository;
import com.honeyshare.notifications.port.UserNotificationPreferencesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InboxNotificationServiceTest {

    @Mock InboxNotificationRepository inboxNotificationRepository;
    @Mock UserNotificationPreferencesRepository preferencesRepository;

    InboxNotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new InboxNotificationService(inboxNotificationRepository, preferencesRepository);
    }

    @Test
    void getNotifications_returnsMappedDtos() {
        InboxNotification n = InboxNotification.create(
                "user-1", "evt-1", "TRANSFER_RECEIVED", "xfer-1", "File ready", "You received a file.");

        when(inboxNotificationRepository.findByUserIdOrderByCreatedAtDesc(eq("user-1"), any(Pageable.class)))
                .thenReturn(List.of(n));

        var list = notificationService.getNotifications("user-1", 0, 20);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).title()).isEqualTo("File ready");
        assertThat(list.get(0).transferId()).isEqualTo("xfer-1");
        assertThat(list.get(0).read()).isFalse();
    }

    @Test
    void markRead_updatesReadAtForMatchingUser() {
        InboxNotification n = InboxNotification.create(
                "user-1", "evt-1", "TRANSFER_RECEIVED", "xfer-1", "File ready", "Message");

        when(inboxNotificationRepository.findById(n.getId())).thenReturn(Optional.of(n));

        notificationService.markRead(n.getId(), "user-1");

        assertThat(n.isRead()).isTrue();
        verify(inboxNotificationRepository).save(n);
    }

    @Test
    void markAllRead_marksAllUnreadNotifications() {
        InboxNotification n1 = InboxNotification.create("user-1", "evt-1", "TYPE", "x1", "T1", "M1");
        InboxNotification n2 = InboxNotification.create("user-1", "evt-2", "TYPE", "x2", "T2", "M2");

        when(inboxNotificationRepository.findByUserIdAndReadAtIsNullOrderByCreatedAtDesc("user-1"))
                .thenReturn(List.of(n1, n2));

        notificationService.markAllRead("user-1");

        assertThat(n1.isRead()).isTrue();
        assertThat(n2.isRead()).isTrue();
        verify(inboxNotificationRepository, times(2)).save(any(InboxNotification.class));
    }

    @Test
    void updatePreferences_savesNewSettings() {
        UserNotificationPreferences existing = UserNotificationPreferences.createDefault("user-1");
        when(preferencesRepository.findByUserId("user-1")).thenReturn(Optional.of(existing));

        var request = new InboxNotificationService.UpdatePreferencesRequest(
                false, true, true, false, true, true);

        var updated = notificationService.updatePreferences("user-1", request);

        assertThat(updated.emailEnabled()).isFalse();
        assertThat(updated.pushEnabled()).isTrue();
        assertThat(updated.notifyOnTransferReceived()).isFalse();
        verify(preferencesRepository).save(existing);
    }
}

