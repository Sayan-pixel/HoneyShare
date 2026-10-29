package com.honeyshare.notifications.domain;

/**
 * State machine for asynchronous outbox delivery.
 */
public enum OutboxEventState {
    PENDING,
    PROCESSING,
    SENT,
    FAILED,
    DEAD_LETTER
}

