package com.charitha.audit.messaging;

import com.charitha.audit.observability.AuditMetrics;
import com.charitha.audit.service.AuditIngestionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PaymentAuditConsumer {
    private final JsonMapper jsonMapper;
    private final AuditIngestionService ingestionService;
    private final AuditMetrics metrics;

    public PaymentAuditConsumer(JsonMapper jsonMapper,
                                AuditIngestionService ingestionService,
                                AuditMetrics metrics) {
        this.jsonMapper = jsonMapper;
        this.ingestionService = ingestionService;
        this.metrics = metrics;
    }

    @KafkaListener(topics = "${topics.payment-created}")
    public void consume(ConsumerRecord<String, String> record) {
        metrics.recordReceived();
        PaymentCreatedEvent event = deserialize(record.value());
        boolean stored = ingestionService.ingest(event, record);
        if (stored) {
            metrics.recordStored();
        } else {
            metrics.recordDuplicate();
        }
    }

    private PaymentCreatedEvent deserialize(String payload) {
        try {
            return jsonMapper.readValue(payload, PaymentCreatedEvent.class);
        } catch (JacksonException ex) {
            metrics.recordMalformed();
            throw new IllegalArgumentException("Invalid payments.created.v1 audit payload", ex);
        }
    }
}
