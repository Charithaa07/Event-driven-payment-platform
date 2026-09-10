package com.charitha.payments.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
class OutboxDeliveryService {
    private final OutboxEventRepository outboxRepository;
    private final int maxAttempts;
    private final long retryBaseMs;
    private final long retryMaxMs;

    OutboxDeliveryService(
            OutboxEventRepository outboxRepository,
            @Value("${outbox.relay.max-attempts:10}") int maxAttempts,
            @Value("${outbox.relay.retry-base-ms:1000}") long retryBaseMs,
            @Value("${outbox.relay.retry-max-ms:60000}") long retryMaxMs) {
        this.outboxRepository = outboxRepository;
        this.maxAttempts = maxAttempts;
        this.retryBaseMs = retryBaseMs;
        this.retryMaxMs = retryMaxMs;
    }

    @Transactional
    public void markPublished(UUID eventId) {
        OutboxEvent event = getEvent(eventId);
        event.markPublished(Instant.now());
    }

    @Transactional
    public void markDeliveryFailure(UUID eventId, Throwable failure) {
        OutboxEvent event = getEvent(eventId);
        boolean terminal = event.getAttempts() >= maxAttempts;
        long backoffMs = retryDelayMs(event.getAttempts());
        String message = failureMessage(failure);
        event.recordFailure(Instant.now().plusMillis(backoffMs), message, terminal);
    }

    private OutboxEvent getEvent(UUID eventId) {
        return outboxRepository.findById(eventId)
                .orElseThrow(() -> new IllegalStateException("Outbox event disappeared: " + eventId));
    }

    private long retryDelayMs(int attempts) {
        int exponent = Math.min(Math.max(attempts - 1, 0), 6);
        long multiplier = 1L << exponent;
        long candidate;
        try {
            candidate = Math.multiplyExact(retryBaseMs, multiplier);
        } catch (ArithmeticException ex) {
            candidate = retryMaxMs;
        }
        return Math.min(candidate, retryMaxMs);
    }

    private String failureMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
