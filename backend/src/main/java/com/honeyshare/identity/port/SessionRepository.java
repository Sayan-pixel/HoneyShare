package com.honeyshare.identity.port;

import com.honeyshare.identity.domain.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface SessionRepository extends JpaRepository<UserSession, String> {

    Optional<UserSession> findBySessionTokenHash(String tokenHash);

    /** Bulk-revoke all sessions for a user (e.g., on password change). */
    @Modifying
    @Query("UPDATE UserSession s SET s.revokedAt = :now WHERE s.userId = :userId AND s.revokedAt IS NULL")
    int revokeAllForUser(String userId, Instant now);
}

