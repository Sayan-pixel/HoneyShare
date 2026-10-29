package com.honeyshare.notifications.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.honeyshare.identity.domain.User;
import com.honeyshare.identity.domain.UserRole;
import com.honeyshare.identity.port.UserRepository;
import com.honeyshare.notifications.domain.*;
import com.honeyshare.notifications.port.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxWorkerTest {

    @Mock OutboxEventRepository outboxEventRepository;
    @Mock InboxNotificationRepository inboxNotificationRepository;
    @Mock UserNotificationPreferencesRepository preferencesRepository;
    @Mock UserRepository userRepository;
    @Mock EmailPort emailPort;
    @Mock PushPort pushPort;

    ObjectMapper objectMapper;
    OutboxWorker outboxWorker;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        outboxWorker = new OutboxWorker(
                outboxEventRepository,
                inboxNotificationRepository,
                preferencesRepository,
                userRepository,
                emailPort,
                pushPort,
                objectMapper
        );
    }

    @Test
    void dispatchEvent_inAppChannel_createsInboxNotification() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "title", "Transfer ready",
                "message", "Your file is available.",
                "transferId", "xfer-1"
        ));

        OutboxEvent event = OutboxEvent.create(
                "user-1", NotificationEventType.TRANSFER_RECEIVED, "evt-1", NotificationChannel.IN_APP, payload);

        when(preferencesRepository.findByUserId("user-1")).thenReturn(Optional.empty()); // default allowed
        when(inboxNotificationRepository.existsByEventIdAndUserId("evt-1", "user-1")).thenReturn(false);

        boolean result = outboxWorker.dispatchEvent(event, Instant.now());

        assertThat(result).isTrue();
        assertThat(event.getState()).isEqualTo(OutboxEventState.SENT);
        verify(inboxNotificationRepository).save(any(InboxNotification.class));
    }

    @Test
    void dispatchEvent_emailChannel_deliversViaEmailPort() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "title", "Subject",
                "message", "Body text"
        ));

        User user = User.create("recipient@example.com", "hash", UserRole.USER);
        OutboxEvent event = OutboxEvent.create(
                user.getId(), NotificationEventType.TRANSFER_RECEIVED, "evt-2", NotificationChannel.EMAIL, payload);

        when(preferencesRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        boolean result = outboxWorker.dispatchEvent(event, Instant.now());

        assertThat(result).isTrue();
        assertThat(event.getState()).isEqualTo(OutboxEventState.SENT);
        verify(emailPort).sendEmail("recipient@example.com", "Subject", "Body text");
    }

    @Test
    void dispatchEvent_pushChannel_enforcesGenericPrivacyPayload() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "title", "Secret document.pdf",
                "message", "Confidential financial data",
                "genericPushText", "You have a new file. Open HoneyShare to view it."
        ));

        OutboxEvent event = OutboxEvent.create(
                "user-1", NotificationEventType.TRANSFER_RECEIVED, "evt-3", NotificationChannel.PUSH, payload);

        when(preferencesRepository.findByUserId("user-1")).thenReturn(Optional.empty());

        boolean result = outboxWorker.dispatchEvent(event, Instant.now());

        assertThat(result).isTrue();
        assertThat(event.getState()).isEqualTo(OutboxEventState.SENT);

        // Verification: pushPort must ONLY receive generic text, NEVER the title or message containing filename
        verify(pushPort).sendPush(eq("user-1"), eq("HoneyShare"), eq("You have a new file. Open HoneyShare to view it."));
        verify(pushPort, never()).sendPush(any(), any(), contains("Secret document.pdf"));
    }

    @Test
    void dispatchEvent_failure_recordsErrorAndSchedulesExponentialBackoff() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of("title", "T", "message", "M"));
        User user = User.create("user@example.com", "hash", UserRole.USER);

        OutboxEvent event = OutboxEvent.create(
                user.getId(), NotificationEventType.TRANSFER_RECEIVED, "evt-4", NotificationChannel.EMAIL, payload);

        when(preferencesRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        doThrow(new RuntimeException("SMTP connection refused")).when(emailPort).sendEmail(any(), any(), any());

        Instant now = Instant.now();
        boolean result = outboxWorker.dispatchEvent(event, now);

        assertThat(result).isFalse();
        assertThat(event.getState()).isEqualTo(OutboxEventState.FAILED);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getLastError()).contains("SMTP connection refused");
        assertThat(event.getNextAttemptAt()).isAfter(now); // Scheduled in the future
    }

    @Test
    void dispatchEvent_channelDisabledInUserPreferences_skipsDeliveryAndMarksSent() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of("title", "T", "message", "M"));
        OutboxEvent event = OutboxEvent.create(
                "user-1", NotificationEventType.TRANSFER_RECEIVED, "evt-5", NotificationChannel.EMAIL, payload);

        UserNotificationPreferences prefs = UserNotificationPreferences.createDefault("user-1");
        prefs.update(false, true, true, true, true, true); // email disabled

        when(preferencesRepository.findByUserId("user-1")).thenReturn(Optional.of(prefs));

        boolean result = outboxWorker.dispatchEvent(event, Instant.now());

        assertThat(result).isTrue();
        assertThat(event.getState()).isEqualTo(OutboxEventState.SENT);
        verify(emailPort, never()).sendEmail(any(), any(), any());
    }
}

