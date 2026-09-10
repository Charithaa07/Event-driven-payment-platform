package com.charitha.transactions.messaging;

import com.charitha.transactions.observability.TransactionConsumerMetrics;
import com.charitha.transactions.service.TransactionProcessor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PaymentCreatedConsumer {
    private final TransactionProcessor transactionProcessor;
    private final JsonMapper jsonMapper;
    private final TransactionConsumerMetrics metrics;

    public PaymentCreatedConsumer(TransactionProcessor transactionProcessor,
                                  JsonMapper jsonMapper,
                                  TransactionConsumerMetrics metrics) {
        this.transactionProcessor = transactionProcessor;
        this.jsonMapper = jsonMapper;
        this.metrics = metrics;
    }

    @KafkaListener(topics = "${topics.payment-created}")
    public void consume(String payload) {
        metrics.recordReceived();
        PaymentCreatedEvent event;
        try {
            event = deserialize(payload);
        } catch (IllegalArgumentException ex) {
            metrics.recordMalformed();
            throw ex;
        }

        long startedAt = System.nanoTime();
        boolean transactionCreated = transactionProcessor.process(event);
        metrics.recordProcessed(transactionCreated, System.nanoTime() - startedAt);
    }

    private PaymentCreatedEvent deserialize(String payload) {
        try {
            return jsonMapper.readValue(payload, PaymentCreatedEvent.class);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Invalid payments.created.v1 event payload", ex);
        }
    }
}
