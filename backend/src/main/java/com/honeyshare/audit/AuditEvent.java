package com.honeyshare.audit;

import com.honeyshare.common.UlidGenerator;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @Column(length = 26, nullable = false, updatable = false)
    private String id;

    @Column(name = "actor_user_id", length = 26)
    private String actorUserId;

    @Column(name = "event_type", length = 100, nullable = false)
    private String eventType;

    @Column(name = "target_type", length = 100)
    private String targetType;

    @Column(name = "target_id", length = 128)
    private String targetId;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "metadata_json", columnDefinition = "JSON")
    private String metadataJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditEvent() {}

    public static AuditEvent create(
            String actorUserId,
            String eventType,
            String targetType,
            String targetId,
            String ipAddress,
            String metadataJson
    ) {
        AuditEvent e = new AuditEvent();
        e.id = UlidGenerator.generate();
        e.actorUserId = actorUserId;
        e.eventType = eventType;
        e.targetType = targetType;
        e.targetId = targetId;
        e.ipAddress = ipAddress;
        e.metadataJson = metadataJson;
        e.createdAt = Instant.now();
        return e;
    }

    public String getId() { return id; }
    public String getActorUserId() { return actorUserId; }
    public String getEventType() { return eventType; }
    public Instant getCreatedAt() { return createdAt; }
}

