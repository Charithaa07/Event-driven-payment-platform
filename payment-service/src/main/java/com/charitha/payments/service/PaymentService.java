package com.charitha.payments.service;

import com.charitha.payments.api.CreatePaymentRequest;
import com.charitha.payments.domain.Payment;
import com.charitha.payments.domain.PaymentRepository;
import com.charitha.payments.domain.PaymentStatus;
import com.charitha.payments.idempotency.RedisIdempotencyStore;
import com.charitha.payments.messaging.PaymentCreatedEvent;
import com.charitha.payments.outbox.OutboxEvent;
import com.charitha.payments.outbox.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {
    private static final String PAYMENT_CREATED_TOPIC = "payments.created.v1";

    private final PaymentRepository repository;
    private final OutboxEventRepository outboxRepository;
    private final JsonMapper jsonMapper;
    private final RedisIdempotencyStore idempotencyStore;

    public PaymentService(PaymentRepository repository,
                          OutboxEventRepository outboxRepository,
                          JsonMapper jsonMapper,
                          RedisIdempotencyStore idempotencyStore) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.jsonMapper = jsonMapper;
        this.idempotencyStore = idempotencyStore;
    }

    @Transactional
    public Payment create(String idempotencyKey, CreatePaymentRequest request) {
        Optional<Payment> cachedPayment = idempotencyStore.findPayment(idempotencyKey);
        if (cachedPayment.isPresent()) {
            Payment payment = cachedPayment.get();
            assertSameRequest(payment, request, idempotencyKey);
            return payment;
        }

        Optional<Payment> persistedPayment = repository.findByIdempotencyKey(idempotencyKey);
        if (persistedPayment.isPresent()) {
            Payment payment = persistedPayment.get();
            assertSameRequest(payment, request, idempotencyKey);
            idempotencyStore.put(idempotencyKey, payment);
            return payment;
        }

        return createOrResolveConcurrentRequest(idempotencyKey, request);
    }

    @Transactional(readOnly = true)
    public Payment get(UUID paymentId) {
        return repository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    private Payment createOrResolveConcurrentRequest(String idempotencyKey, CreatePaymentRequest request) {
        Instant now = Instant.now();
        Payment candidate = new Payment(
                UUID.randomUUID(),
                idempotencyKey,
                request.amount(),
                request.currency(),
                request.customerId(),
                PaymentStatus.ACCEPTED,
                now
        );

        int inserted = repository.insertIfAbsent(
                candidate.getId(),
                candidate.getIdempotencyKey(),
                candidate.getAmount(),
                candidate.getCurrency(),
                candidate.getCustomerId(),
                candidate.getStatus().name(),
                candidate.getCreatedAt()
        );

        if (inserted == 0) {
            Payment existing = repository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency conflict was detected but the winning payment could not be loaded"
                    ));
            assertSameRequest(existing, request, idempotencyKey);
            idempotencyStore.put(idempotencyKey, existing);
            return existing;
        }

        PaymentCreatedEvent event = new PaymentCreatedEvent(
                UUID.randomUUID(),
                candidate.getId(),
                candidate.getAmount(),
                candidate.getCurrency(),
                candidate.getCustomerId(),
                now
        );

        outboxRepository.save(new OutboxEvent(
                event.eventId(),
                "PAYMENT",
                candidate.getId(),
                "PAYMENT_CREATED_V1",
                PAYMENT_CREATED_TOPIC,
                serialize(event),
                now
        ));

        cacheAfterCommit(idempotencyKey, candidate);
        return candidate;
    }

    private void assertSameRequest(Payment payment,
                                   CreatePaymentRequest request,
                                   String idempotencyKey) {
        boolean sameAmount = payment.getAmount().compareTo(request.amount()) == 0;
        boolean sameCurrency = payment.getCurrency().equals(request.currency());
        boolean sameCustomer = payment.getCustomerId().equals(request.customerId());

        if (!sameAmount || !sameCurrency || !sameCustomer) {
            throw new IdempotencyConflictException(idempotencyKey);
        }
    }

    private void cacheAfterCommit(String idempotencyKey, Payment payment) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            idempotencyStore.put(idempotencyKey, payment);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                idempotencyStore.put(idempotencyKey, payment);
            }
        });
    }

    private String serialize(PaymentCreatedEvent event) {
        try {
            return jsonMapper.writeValueAsString(event);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Failed to serialize payment event", ex);
        }
    }
}
