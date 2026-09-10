package com.charitha.notifications.service;

import com.charitha.notifications.domain.NotificationChannel;
import com.charitha.notifications.domain.NotificationDeliveryRepository;
import com.charitha.notifications.messaging.PaymentCreatedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class NotificationIngestionService {
    private static final String PAYMENT_ACCEPTED_TEMPLATE = "PAYMENT_ACCEPTED";

    private final NotificationDeliveryRepository repository;

    public NotificationIngestionService(NotificationDeliveryRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public NotificationIngestionOutcome accept(PaymentCreatedEvent event) {
        Instant now = Instant.now();
        int inserted = repository.insertPending(
                UUID.randomUUID(),
                event.eventId(),
                event.paymentId(),
                event.customerId(),
                NotificationChannel.EMAIL.name(),
                PAYMENT_ACCEPTED_TEMPLATE,
                event.amount(),
                event.currency(),
                now
        );
        return inserted == 1
                ? NotificationIngestionOutcome.STORED
                : NotificationIngestionOutcome.DUPLICATE;
    }
}
