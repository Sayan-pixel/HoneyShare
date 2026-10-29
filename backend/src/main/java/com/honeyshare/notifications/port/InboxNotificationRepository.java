package com.honeyshare.notifications.port;

import com.honeyshare.notifications.domain.InboxNotification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InboxNotificationRepository extends JpaRepository<InboxNotification, String> {

    List<InboxNotification> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    List<InboxNotification> findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(String userId);

    long countByUserIdAndReadAtIsNull(String userId);

    boolean existsByEventIdAndUserId(String eventId, String userId);
}

