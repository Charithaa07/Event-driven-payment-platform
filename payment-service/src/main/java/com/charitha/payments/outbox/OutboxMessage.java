package com.charitha.payments.outbox;

import java.util.UUID;

record OutboxMessage(UUID id, UUID aggregateId, String topic, String payload) {
    static OutboxMessage from(OutboxEvent event) {
        return new OutboxMessage(
                event.getId(),
                event.getAggregateId(),
                event.getTopic(),
                event.getPayload()
        );
    }
}
