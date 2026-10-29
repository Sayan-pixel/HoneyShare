package com.honeyshare.notifications.domain;

import com.honeyshare.common.UlidGenerator;
import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Persisted outbox event for the transactional outbox pattern.
 * Written in the same MySQL transaction as domain mutations, then delivered asynchronously.
 * Enforces exponential backoff with jitter and deduplication.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @Column(length = 26, nullable = false, updatable = false)
    private String id;

    @Column(name = "user_id", length = 26, nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 60, nullable = false)
    private NotificationEventType eventType;

    @Column(name = "event_id", length = 128, nullable = false)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 20, nullable = false)
    private NotificationChannel channel;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 20, nullable = false)
    private OutboxEventState state;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OutboxEvent() {}

    public static OutboxEvent create(
            String userId,
            NotificationEventType eventType,
            String eventId,
            NotificationChannel channel,
            String payload
    ) {
        OutboxEvent event = new OutboxEvent();
        event.id = UlidGenerator.generate();
        event.userId = userId;
        event.eventType = eventType;
        event.eventId = eventId;
        event.channel = channel;
        event.payload = payload;
        event.state = OutboxEventState.PENDING;
        event.attemptCount = 0;
        event.maxAttempts = 5;
        Instant now = Instant.now();
        event.nextAttemptAt = now;
        event.createdAt = now;
        event.updatedAt = now;
        return event;
    }

    public void markProcessing() {
        this.state = OutboxEventState.PROCESSING;
        this.updatedAt = Instant.now();
    }

    public void markSent(Instant now) {
        this.state = OutboxEventState.SENT;
        this.sentAt = now;
        this.updatedAt = now;
        this.lastError = null;
    }

    /**
     * Record a delivery failure and calculate exponential backoff with jitter:
     * backoff = base * (2 ^ attemptCount) + random_jitter
     */
    public void recordFailure(String errorMessage, Instant now) {
        this.attemptCount++;
        this.lastError = errorMessage;
        this.updatedAt = now;

        if (this.attemptCount >= this.maxAttempts) {
            this.state = OutboxEventState.DEAD_LETTER;
        } else {
            this.state = OutboxEventState.FAILED;
            // 2^attempts * 1 second + 0..500ms jitter
            long baseDelaySeconds = (long) Math.min(300, Math.pow(2, this.attemptCount));
            long jitterMs = ThreadLocalRandom.current().nextLong(500);
            this.nextAttemptAt = now.plus(Duration.ofSeconds(baseDelaySeconds)).plusMillis(jitterMs);
        }
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public NotificationEventType getEventType() { return eventType; }
    public String getEventId() { return eventId; }
    public NotificationChannel getChannel() { return channel; }
    public String getPayload() { return payload; }
    public OutboxEventState getState() { return state; }
    public int getAttemptCount() { return attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getSentAt() { return sentAt; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

