package com.charitha.audit.api;

import com.charitha.audit.domain.AuditEvent;

import java.time.Instant;
import java.util.UUID;

public record AuditEventSummary(
        UUID eventId,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        String customerId,
        String sourceTopic,
        int sourcePartition,
        long sourceOffset,
        String recordSha256,
        Instant occurredAt,
        Instant recordedAt
) {
    public static AuditEventSummary from(AuditEvent event) {
        return new AuditEventSummary(
                event.getEventId(),
                event.getEventType(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getCustomerId(),
                event.getSourceTopic(),
                event.getSourcePartition(),
                event.getSourceOffset(),
                event.getRecordSha256(),
                event.getOccurredAt(),
                event.getRecordedAt()
        );
    }
}
