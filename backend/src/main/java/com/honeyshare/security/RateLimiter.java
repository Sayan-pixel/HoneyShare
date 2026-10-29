package com.honeyshare.security;

import com.honeyshare.common.exception.RateLimitExceededException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory rate limiter using Bucket4j token buckets.
 *
 * <p>This is a process-local limiter suitable for a single-instance beta.
 * Replace with a distributed implementation (e.g., Redis + Bucket4j) before
 * scaling to multiple instances.
 *
 * <p>Buckets are keyed by {@code operation:key} (e.g., {@code login:user@example.com}).
 */
@Component
public class RateLimiter {

    // Each operation bucket: 5 attempts per 15-minute window
    private static final int DEFAULT_CAPACITY = 5;
    private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(15);

    // Stricter: 3 attempts per 5 minutes for sensitive ops
    private static final int SENSITIVE_CAPACITY = 3;
    private static final Duration SENSITIVE_WINDOW = Duration.ofMinutes(5);

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * Check and consume a token for a given operation and key.
     * Throws {@link RateLimitExceededException} if the bucket is exhausted.
     *
     * @param operation   e.g., "login", "password-reset", "mfa"
     * @param key         e.g., email address or IP
     */
    public void check(String operation, String key) {
        String bucketKey = operation + ":" + key;
        Bucket bucket = buckets.computeIfAbsent(bucketKey, k -> buildBucket(operation));
        if (!bucket.tryConsume(1)) {
            throw new RateLimitExceededException(operation);
        }
    }

    private Bucket buildBucket(String operation) {
        boolean sensitive = "mfa".equals(operation) || "password-reset".equals(operation);
        int capacity = sensitive ? SENSITIVE_CAPACITY : DEFAULT_CAPACITY;
        Duration window = sensitive ? SENSITIVE_WINDOW : DEFAULT_WINDOW;

        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillIntervally(capacity, window)
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}

