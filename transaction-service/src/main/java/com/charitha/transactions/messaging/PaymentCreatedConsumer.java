package com.charitha.transactions.messaging;

import com.charitha.transactions.service.TransactionProcessor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PaymentCreatedConsumer {
    private final TransactionProcessor transactionProcessor;
    private final JsonMapper jsonMapper;

    public PaymentCreatedConsumer(TransactionProcessor transactionProcessor, JsonMapper jsonMapper) {
        this.transactionProcessor = transactionProcessor;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(topics = "${topics.payment-created}")
    public void consume(String payload) {
        PaymentCreatedEvent event = deserialize(payload);
        transactionProcessor.process(event);
    }

    private PaymentCreatedEvent deserialize(String payload) {
        try {
            return jsonMapper.readValue(payload, PaymentCreatedEvent.class);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Invalid payments.created.v1 event payload", ex);
        }
    }
}
