package com.honeyshare.notifications.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.honeyshare.notifications.domain.NotificationChannel;
import com.honeyshare.notifications.domain.NotificationEventType;
import com.honeyshare.notifications.domain.OutboxEvent;
import com.honeyshare.notifications.port.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    @Mock OutboxEventRepository outboxEventRepository;

    ObjectMapper objectMapper;
    OutboxService outboxService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        outboxService = new OutboxService(outboxEventRepository, objectMapper);
    }

    @Test
    void publishEvent_createsEntriesAcrossChannels() {
        when(outboxEventRepository.existsByEventIdAndChannel(any(), any())).thenReturn(false);

        outboxService.publishEvent(
                "user-1",
                NotificationEventType.TRANSFER_RECEIVED,
                "evt-100",
                "New file available",
                "A file was shared with you.",
                "You have a new file. Open HoneyShare to view it.",
                "transfer-123"
        );

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, times(3)).save(captor.capture());

        List<OutboxEvent> saved = captor.getAllValues();
        assertThat(saved).hasSize(3);
        assertThat(saved).extracting(OutboxEvent::getChannel)
                .containsExactlyInAnyOrder(NotificationChannel.IN_APP, NotificationChannel.EMAIL, NotificationChannel.PUSH);
        assertThat(saved).allMatch(e -> e.getEventId().equals("evt-100"));
        assertThat(saved).allMatch(e -> e.getUserId().equals("user-1"));
    }

    @Test
    void publishEvent_deduplicatesByEventIdAndChannel() {
        when(outboxEventRepository.existsByEventIdAndChannel(eq("evt-100"), eq(NotificationChannel.IN_APP))).thenReturn(true);
        when(outboxEventRepository.existsByEventIdAndChannel(eq("evt-100"), eq(NotificationChannel.EMAIL))).thenReturn(false);
        when(outboxEventRepository.existsByEventIdAndChannel(eq("evt-100"), eq(NotificationChannel.PUSH))).thenReturn(false);

        outboxService.publishEvent(
                "user-1",
                NotificationEventType.TRANSFER_RECEIVED,
                "evt-100",
                "New file",
                "Message",
                "Generic text",
                "transfer-123"
        );

        // IN_APP is skipped because it already exists
        verify(outboxEventRepository, times(2)).save(any(OutboxEvent.class));
    }
}

