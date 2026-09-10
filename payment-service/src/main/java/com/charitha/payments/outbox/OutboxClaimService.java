package com.charitha.payments.outbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
class OutboxClaimService {
    private final OutboxEventRepository outboxRepository;

    OutboxClaimService(OutboxEventRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public List<OutboxMessage> claimBatch() {
        List<OutboxEvent> events = outboxRepository.findClaimableBatch();
        Instant now = Instant.now();
        events.forEach(event -> event.claim(now));
        return events.stream().map(OutboxMessage::from).toList();
    }
}
