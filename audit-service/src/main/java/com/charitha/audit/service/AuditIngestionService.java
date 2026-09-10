package com.charitha.audit.service;

import com.charitha.audit.domain.AuditEventRepository;
import com.charitha.audit.messaging.PaymentCreatedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AuditIngestionService {
    private static final String EVENT_TYPE = "PAYMENT_CREATED_V1";
    private static final String AGGREGATE_TYPE = "PAYMENT";

    private final AuditEventRepository repository;
    private final AuditIntegrityService integrityService;

    public AuditIngestionService(AuditEventRepository repository,
                                 AuditIntegrityService integrityService) {
        this.repository = repository;
        this.integrityService = integrityService;
    }

    @Transactional
    public boolean ingest(PaymentCreatedEvent event,
                          ConsumerRecord<String, String> record) {
        validate(event);
        String hash = integrityService.compute(
                event.eventId(),
                EVENT_TYPE,
                event.paymentId(),
                event.customerId(),
                record.topic(),
                record.partition(),
                record.offset(),
                record.value()
        );

        return repository.insertIfAbsent(
                event.eventId(),
                EVENT_TYPE,
                AGGREGATE_TYPE,
                event.paymentId(),
                event.customerId(),
                record.topic(),
                record.partition(),
                record.offset(),
                record.value(),
                hash,
                event.occurredAt(),
                Instant.now()
        ) == 1;
    }

    private void validate(PaymentCreatedEvent event) {
        if (event.eventId() == null || event.paymentId() == null || event.occurredAt() == null) {
            throw new IllegalArgumentException("Payment audit event is missing required identifiers or timestamp");
        }
        if (event.customerId() == null || event.customerId().isBlank() || event.customerId().length() > 120) {
            throw new IllegalArgumentException("Payment audit event has an invalid customerId");
        }
        if (event.amount() == null || event.amount().signum() <= 0) {
            throw new IllegalArgumentException("Payment audit event has an invalid amount");
        }
        if (event.currency() == null || event.currency().length() != 3) {
            throw new IllegalArgumentException("Payment audit event has an invalid currency");
        }
    }
}
