package com.honeyshare.notifications.port;

import com.honeyshare.notifications.domain.NotificationChannel;
import com.honeyshare.notifications.domain.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    boolean existsByEventIdAndChannel(String eventId, NotificationChannel channel);

    @Query("SELECT e FROM OutboxEvent e WHERE e.state IN ('PENDING', 'FAILED') AND e.nextAttemptAt <= :now AND e.attemptCount < e.maxAttempts ORDER BY e.createdAt ASC")
    List<OutboxEvent> findPendingEvents(Instant now, Pageable pageable);

    @Query("SELECT COUNT(e) FROM OutboxEvent e WHERE e.state = 'DEAD_LETTER'")
    long countDeadLetterEvents();
}

