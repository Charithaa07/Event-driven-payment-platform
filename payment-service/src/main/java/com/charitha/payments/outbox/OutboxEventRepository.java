package com.charitha.payments.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    @Query(value = """
            SELECT *
            FROM outbox_events
            WHERE (status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP)
               OR (status = 'PROCESSING' AND claimed_at < CURRENT_TIMESTAMP - INTERVAL '30 seconds')
            ORDER BY created_at ASC
            LIMIT 50
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findClaimableBatch();
}
