package com.charitha.audit.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findByAggregateIdOrderByOccurredAtAscRecordedAtAsc(UUID aggregateId);

    @Modifying
    @Query(value = """
            INSERT INTO audit_events (
                event_id, event_type, aggregate_type, aggregate_id, customer_id,
                source_topic, source_partition, source_offset, payload, record_sha256,
                occurred_at, recorded_at
            ) VALUES (
                :eventId, :eventType, :aggregateType, :aggregateId, :customerId,
                :sourceTopic, :sourcePartition, :sourceOffset, :payload, :recordSha256,
                :occurredAt, :recordedAt
            )
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("eventId") UUID eventId,
            @Param("eventType") String eventType,
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") UUID aggregateId,
            @Param("customerId") String customerId,
            @Param("sourceTopic") String sourceTopic,
            @Param("sourcePartition") int sourcePartition,
            @Param("sourceOffset") long sourceOffset,
            @Param("payload") String payload,
            @Param("recordSha256") String recordSha256,
            @Param("occurredAt") Instant occurredAt,
            @Param("recordedAt") Instant recordedAt);
}
