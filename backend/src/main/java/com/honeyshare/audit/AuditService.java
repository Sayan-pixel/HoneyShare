package com.honeyshare.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records security-sensitive events to the {@code audit_events} table.
 *
 * <p>The service uses a separate transaction (REQUIRES_NEW) so that
 * audit events are committed even when the outer transaction rolls back.
 * Writes are async to avoid blocking the request thread on the audit insert.
 *
 * <p>IMPORTANT: Never pass passwords, session tokens, cryptographic keys,
 * recovery secrets, or presigned URLs to this service.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            String actorUserId,
            String eventType,
            String targetType,
            String targetId,
            String ipAddress,
            String metadataJson
    ) {
        try {
            AuditEvent event = AuditEvent.create(
                    actorUserId, eventType, targetType, targetId, ipAddress, metadataJson);
            repository.save(event);
        } catch (Exception e) {
            // Audit failure must not crash the main operation.
            // Log at ERROR so ops can investigate and replay if needed.
            log.error("AUDIT_FAILURE event={} actor={} error={}", eventType, actorUserId, e.getMessage());
        }
    }

    /** Convenience overload without metadata. */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actorUserId, String eventType, String targetType, String targetId, String ipAddress) {
        record(actorUserId, eventType, targetType, targetId, ipAddress, null);
    }
}

