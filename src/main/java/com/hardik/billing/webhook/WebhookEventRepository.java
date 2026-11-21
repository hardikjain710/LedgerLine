package com.hardik.billing.webhook;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, String> {

    @Modifying
    @Query(value = """
            INSERT INTO webhook_events (event_id, type, payload)
            VALUES (:id, :type, CAST(:payload AS jsonb))
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfMissing(@Param("id") String id, @Param("type") String type, @Param("payload") String payload);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from WebhookEvent event where event.eventId = :id")
    Optional<WebhookEvent> findByIdForUpdate(@Param("id") String id);
}
