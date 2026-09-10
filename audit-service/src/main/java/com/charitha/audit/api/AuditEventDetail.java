package com.charitha.audit.api;

import com.charitha.audit.domain.AuditEvent;

import java.time.Instant;
import java.util.UUID;

public record AuditEventDetail(
        UUID eventId,
        String eventType,
        String aggregateType,
        UUID aggregateId,
        String customerId,
        String sourceTopic,
        int sourcePartition,
        long sourceOffset,
        String payload,
        String recordSha256,
        boolean integrityValid,
        Instant occurredAt,
        Instant recordedAt
) {
    public static AuditEventDetail from(AuditEvent event, boolean integrityValid) {
        return new AuditEventDetail(
                event.getEventId(),
                event.getEventType(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getCustomerId(),
                event.getSourceTopic(),
                event.getSourcePartition(),
                event.getSourceOffset(),
                event.getPayload(),
                event.getRecordSha256(),
                integrityValid,
                event.getOccurredAt(),
                event.getRecordedAt()
        );
    }
}
