package com.charitha.notifications.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {

    List<NotificationDelivery> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    @Modifying
    @Query(value = """
            INSERT INTO notification_deliveries (
                id, source_event_id, payment_id, customer_id, channel, template,
                amount, currency, status, attempt_count, next_attempt_at,
                created_at, updated_at, version
            ) VALUES (
                :id, :sourceEventId, :paymentId, :customerId, :channel, :template,
                :amount, :currency, 'PENDING', 0, :now,
                :now, :now, 0
            )
            ON CONFLICT (source_event_id, channel) DO NOTHING
            """, nativeQuery = true)
    int insertPending(
            @Param("id") UUID id,
            @Param("sourceEventId") UUID sourceEventId,
            @Param("paymentId") UUID paymentId,
            @Param("customerId") String customerId,
            @Param("channel") String channel,
            @Param("template") String template,
            @Param("amount") BigDecimal amount,
            @Param("currency") String currency,
            @Param("now") Instant now);

    @Query(value = """
            SELECT *
            FROM notification_deliveries
            WHERE (
                (status IN ('PENDING', 'RETRY_PENDING') AND next_attempt_at <= :now)
                OR (status = 'PROCESSING' AND processing_started_at <= :staleBefore)
            )
            ORDER BY created_at ASC
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
            """, nativeQuery = true)
    List<NotificationDelivery> lockDueForDispatch(
            @Param("now") Instant now,
            @Param("staleBefore") Instant staleBefore,
            @Param("batchSize") int batchSize);
}
