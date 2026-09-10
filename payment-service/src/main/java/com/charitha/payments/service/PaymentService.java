package com.charitha.payments.service;

import com.charitha.payments.api.CreatePaymentRequest;
import com.charitha.payments.domain.Payment;
import com.charitha.payments.domain.PaymentRepository;
import com.charitha.payments.domain.PaymentStatus;
import com.charitha.payments.messaging.PaymentCreatedEvent;
import com.charitha.payments.outbox.OutboxEvent;
import com.charitha.payments.outbox.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

@Service
public class PaymentService {
    private static final String PAYMENT_CREATED_TOPIC = "payments.created.v1";

    private final PaymentRepository repository;
    private final OutboxEventRepository outboxRepository;
    private final JsonMapper jsonMapper;

    public PaymentService(PaymentRepository repository,
                          OutboxEventRepository outboxRepository,
                          JsonMapper jsonMapper) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public Payment create(String idempotencyKey, CreatePaymentRequest request) {
        return repository.findByIdempotencyKey(idempotencyKey)
                .orElseGet(() -> createNew(idempotencyKey, request));
    }

    @Transactional(readOnly = true)
    public Payment get(UUID paymentId) {
        return repository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    private Payment createNew(String idempotencyKey, CreatePaymentRequest request) {
        Instant now = Instant.now();
        Payment payment = new Payment(
                UUID.randomUUID(),
                idempotencyKey,
                request.amount(),
                request.currency(),
                request.customerId(),
                PaymentStatus.ACCEPTED,
                now
        );

        Payment saved = repository.save(payment);
        PaymentCreatedEvent event = new PaymentCreatedEvent(
                UUID.randomUUID(),
                saved.getId(),
                saved.getAmount(),
                saved.getCurrency(),
                saved.getCustomerId(),
                now
        );

        outboxRepository.save(new OutboxEvent(
                event.eventId(),
                "PAYMENT",
                saved.getId(),
                "PAYMENT_CREATED_V1",
                PAYMENT_CREATED_TOPIC,
                serialize(event),
                now
        ));

        return saved;
    }

    private String serialize(PaymentCreatedEvent event) {
        try {
            return jsonMapper.writeValueAsString(event);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize payment event", ex);
        }
    }
}
