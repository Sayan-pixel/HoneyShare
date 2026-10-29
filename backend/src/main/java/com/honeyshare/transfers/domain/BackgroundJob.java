package com.honeyshare.transfers.domain;

import com.honeyshare.common.UlidGenerator;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * A persistent background job for outbox delivery, retries, storage reconciliation, and deletion.
 */
@Entity
@Table(name = "background_jobs")
public class BackgroundJob {

    @Id
    @Column(length = 26, nullable = false, updatable = false)
    private String id;

    @Column(name = "job_type", length = 100, nullable = false)
    private String jobType;

    @Column(name = "aggregate_id", length = 128, nullable = false)
    private String aggregateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 30)
    private JobState state;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "locked_by", length = 128)
    private String lockedBy;

    @Column(name = "last_error_code", length = 100)
    private String lastErrorCode;

    @Column(name = "last_error_msg", columnDefinition = "TEXT")
    private String lastErrorMsg;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public enum JobState {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        DEAD_LETTER
    }

    protected BackgroundJob() {}

    public static BackgroundJob create(String jobType, String aggregateId, int maxAttempts, Instant firstAttemptAt) {
        BackgroundJob j = new BackgroundJob();
        j.id = UlidGenerator.generate();
        j.jobType = jobType;
        j.aggregateId = aggregateId;
        j.state = JobState.PENDING;
        j.attemptCount = 0;
        j.maxAttempts = maxAttempts > 0 ? maxAttempts : 5;
        j.nextAttemptAt = firstAttemptAt != null ? firstAttemptAt : Instant.now();
        Instant now = Instant.now();
        j.createdAt = now;
        j.updatedAt = now;
        return j;
    }

    public void markCompleted(Instant now) {
        this.state = JobState.COMPLETED;
        this.lockedAt = null;
        this.lockedBy = null;
        this.updatedAt = now;
    }

    public void recordFailure(String errorCode, String errorMessage, Instant nextAttempt, Instant now) {
        this.attemptCount++;
        this.lastErrorCode = errorCode;
        this.lastErrorMsg = errorMessage;
        this.lockedAt = null;
        this.lockedBy = null;
        this.updatedAt = now;

        if (this.attemptCount >= this.maxAttempts) {
            this.state = JobState.DEAD_LETTER;
        } else {
            this.state = JobState.PENDING;
            this.nextAttemptAt = nextAttempt;
        }
    }

    public String getId() { return id; }
    public String getJobType() { return jobType; }
    public String getAggregateId() { return aggregateId; }
    public JobState getState() { return state; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getLockedAt() { return lockedAt; }
    public String getLockedBy() { return lockedBy; }
    public String getLastErrorCode() { return lastErrorCode; }
    public String getLastErrorMsg() { return lastErrorMsg; }
}

