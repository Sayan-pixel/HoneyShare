package com.honeyshare.notifications.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.honeyshare.notifications.domain.NotificationChannel;
import com.honeyshare.notifications.domain.NotificationEventType;
import com.honeyshare.notifications.domain.OutboxEvent;
import com.honeyshare.notifications.port.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Service to publish domain events to the transactional outbox.
 * Guarantees atomicity between domain changes and notification intent.
 * Complies with HoneyShare Specification Section 3.8 & Section 12.
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Enqueue notification events across channels (IN_APP, EMAIL, PUSH) in the current database transaction.
     * Enforces deduplication by eventId.
     */
    @Transactional
    public void publishEvent(
            String userId,
            NotificationEventType eventType,
            String eventId,
            String title,
            String message,
            String genericPushText,
            String transferId
    ) {
        Map<String, String> payloadMap = Map.of(
                "title", title != null ? title : "Notification",
                "message", message != null ? message : "",
                "genericPushText", genericPushText != null ? genericPushText : "You have a new update. Open HoneyShare to view it.",
                "transferId", transferId != null ? transferId : ""
        );

        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(payloadMap);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize notification payload for eventId {}: {}", eventId, e.getMessage());
            jsonPayload = "{}";
        }

        // Publish to IN_APP channel
        createIfAbsent(userId, eventType, eventId, NotificationChannel.IN_APP, jsonPayload);

        // Publish to EMAIL channel
        createIfAbsent(userId, eventType, eventId, NotificationChannel.EMAIL, jsonPayload);

        // Publish to PUSH channel
        createIfAbsent(userId, eventType, eventId, NotificationChannel.PUSH, jsonPayload);
    }

    private void createIfAbsent(
            String userId,
            NotificationEventType eventType,
            String eventId,
            NotificationChannel channel,
            String payload
    ) {
        if (!outboxEventRepository.existsByEventIdAndChannel(eventId, channel)) {
            OutboxEvent event = OutboxEvent.create(userId, eventType, eventId, channel, payload);
            outboxEventRepository.save(event);
            log.debug("Enqueued outbox event: eventId={} channel={}", eventId, channel);
        }
    }
}

