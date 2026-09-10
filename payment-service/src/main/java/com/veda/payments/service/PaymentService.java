package com.veda.payments.service;

import com.veda.payments.api.CreatePaymentRequest;
import com.veda.payments.domain.Payment;
import com.veda.payments.domain.PaymentRepository;
import com.veda.payments.domain.PaymentStatus;
import com.veda.payments.messaging.PaymentCreatedEvent;
import com.veda.payments.messaging.PaymentEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

@Service
public class PaymentService {
    private final PaymentRepository repository;
    private final PaymentEventPublisher eventPublisher;

    public PaymentService(PaymentRepository repository, PaymentEventPublisher eventPublisher) {
        this.repository = repository; this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Payment create(String idempotencyKey, CreatePaymentRequest request) {
        return repository.findByIdempotencyKey(idempotencyKey).orElseGet(() -> createNew(idempotencyKey, request));
    }

    @Transactional(readOnly = true)
    public Payment get(UUID paymentId) {
        return repository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    private Payment createNew(String idempotencyKey, CreatePaymentRequest request) {
        Instant now = Instant.now();
        Payment payment = new Payment(UUID.randomUUID(), idempotencyKey, request.amount(), request.currency(), request.customerId(), PaymentStatus.ACCEPTED, now);
        Payment saved = repository.save(payment);
        eventPublisher.publish(new PaymentCreatedEvent(UUID.randomUUID(), saved.getId(), saved.getAmount(), saved.getCurrency(), saved.getCustomerId(), now));
        return saved;
    }
}
