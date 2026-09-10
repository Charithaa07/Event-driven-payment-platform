package com.charitha.notifications.messaging;

import com.charitha.notifications.observability.NotificationMetrics;
import com.charitha.notifications.service.NotificationIngestionOutcome;
import com.charitha.notifications.service.NotificationIngestionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PaymentNotificationConsumer {
    private final JsonMapper jsonMapper;
    private final NotificationIngestionService ingestionService;
    private final NotificationMetrics metrics;

    public PaymentNotificationConsumer(
            JsonMapper jsonMapper,
            NotificationIngestionService ingestionService,
            NotificationMetrics metrics) {
        this.jsonMapper = jsonMapper;
        this.ingestionService = ingestionService;
        this.metrics = metrics;
    }

    @KafkaListener(topics = "${topics.payment-created}", groupId = "${spring.kafka.consumer.group-id}")
    public void onPaymentCreated(ConsumerRecord<String, String> record) {
        metrics.recordIngestion("received");
        PaymentCreatedEvent event;
        try {
            event = jsonMapper.readValue(record.value(), PaymentCreatedEvent.class);
        } catch (JacksonException ex) {
            metrics.recordIngestion("malformed");
            throw new IllegalArgumentException("Payment-created payload is not valid JSON", ex);
        }

        try {
            validate(event);
        } catch (IllegalArgumentException ex) {
            metrics.recordIngestion("malformed");
            throw ex;
        }

        NotificationIngestionOutcome outcome = ingestionService.accept(event);
        metrics.recordIngestion(outcome == NotificationIngestionOutcome.STORED ? "stored" : "duplicate");
    }

    private void validate(PaymentCreatedEvent event) {
        if (event.eventId() == null || event.paymentId() == null || event.occurredAt() == null) {
            throw new IllegalArgumentException("Payment-created event is missing required identifiers or timestamp");
        }
        if (event.amount() == null || event.amount().signum() <= 0) {
            throw new IllegalArgumentException("Payment-created event amount must be positive");
        }
        if (event.currency() == null || !event.currency().matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Payment-created event currency must be a three-letter uppercase code");
        }
        if (event.customerId() == null || event.customerId().isBlank() || event.customerId().length() > 120) {
            throw new IllegalArgumentException("Payment-created event customerId is invalid");
        }
    }
}
