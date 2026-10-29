package com.honeyshare.notifications.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.honeyshare.identity.domain.User;
import com.honeyshare.identity.port.UserRepository;
import com.honeyshare.notifications.domain.InboxNotification;
import com.honeyshare.notifications.domain.OutboxEvent;
import com.honeyshare.notifications.domain.UserNotificationPreferences;
import com.honeyshare.notifications.port.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Background worker processing durable outbox events.
 * Dispatches to in-app inbox, email, and push channels.
 * Retries transient failures with exponential backoff and jitter.
 * Complies with HoneyShare Specification Section 3.8, Section 5, and Section 12.
 */
@Service
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

    private final OutboxEventRepository outboxEventRepository;
    private final InboxNotificationRepository inboxNotificationRepository;
    private final UserNotificationPreferencesRepository preferencesRepository;
    private final UserRepository userRepository;
    private final EmailPort emailPort;
    private final PushPort pushPort;
    private final ObjectMapper objectMapper;

    public OutboxWorker(
            OutboxEventRepository outboxEventRepository,
            InboxNotificationRepository inboxNotificationRepository,
            UserNotificationPreferencesRepository preferencesRepository,
            UserRepository userRepository,
            EmailPort emailPort,
            PushPort pushPort,
            ObjectMapper objectMapper
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.inboxNotificationRepository = inboxNotificationRepository;
        this.preferencesRepository = preferencesRepository;
        this.userRepository = userRepository;
        this.emailPort = emailPort;
        this.pushPort = pushPort;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${honeyshare.jobs.outbox-interval-ms:5000}")
    public void runOutboxWorker() {
        processPendingEvents(Instant.now(), 50);
    }

    /**
     * Process a batch of pending outbox events.
     */
    @Transactional
    public int processPendingEvents(Instant now, int batchSize) {
        List<OutboxEvent> pending = outboxEventRepository.findPendingEvents(now, PageRequest.of(0, batchSize));
        int successCount = 0;

        for (OutboxEvent event : pending) {
            boolean success = dispatchEvent(event, now);
            if (success) {
                successCount++;
            }
        }

        return successCount;
    }

    /**
     * Dispatch an individual outbox event according to channel and user preferences.
     */
    @Transactional
    public boolean dispatchEvent(OutboxEvent event, Instant now) {
        event.markProcessing();
        outboxEventRepository.save(event);

        try {
            // 1. Check user notification preferences
            UserNotificationPreferences prefs = preferencesRepository.findByUserId(event.getUserId())
                    .orElseGet(() -> UserNotificationPreferences.createDefault(event.getUserId()));

            if (!prefs.allowsChannel(event.getChannel())) {
                log.debug("Notification channel {} disabled by user preferences: userId={}", event.getChannel(), event.getUserId());
                event.markSent(now);
                outboxEventRepository.save(event);
                return true;
            }

            // 2. Parse payload safely
            Map<String, String> payload = parsePayload(event.getPayload());
            String title = payload.getOrDefault("title", "Update from HoneyShare");
            String message = payload.getOrDefault("message", "");
            String genericPushText = payload.getOrDefault("genericPushText", "You have a new file. Open HoneyShare to view it.");
            String transferId = payload.getOrDefault("transferId", "");

            // 3. Dispatch to appropriate channel
            switch (event.getChannel()) {
                case IN_APP -> {
                    if (!inboxNotificationRepository.existsByEventIdAndUserId(event.getEventId(), event.getUserId())) {
                        InboxNotification notification = InboxNotification.create(
                                event.getUserId(), event.getEventId(), event.getEventType().name(), transferId, title, message);
                        inboxNotificationRepository.save(notification);
                    }
                }
                case EMAIL -> {
                    User user = userRepository.findById(event.getUserId()).orElse(null);
                    if (user != null && user.getEmailNormalized() != null) {
                        emailPort.sendEmail(user.getEmailNormalized(), title, message);
                    }
                }
                case PUSH -> {
                    // FR-NOTIFY-04: Enforce generic push payload. Never include filenames or file content.
                    pushPort.sendPush(event.getUserId(), "HoneyShare", genericPushText);
                }
            }

            event.markSent(now);
            outboxEventRepository.save(event);
            return true;
        } catch (Exception e) {
            log.warn("Failed to dispatch outbox event {}: {}", event.getId(), e.getMessage());
            event.recordFailure(e.getMessage(), now);
            outboxEventRepository.save(event);
            return false;
        }
    }

    private Map<String, String> parsePayload(String rawJson) {
        try {
            return objectMapper.readValue(rawJson, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}

