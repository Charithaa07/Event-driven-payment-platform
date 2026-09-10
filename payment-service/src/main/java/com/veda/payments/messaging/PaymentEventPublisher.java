package com.veda.payments.messaging;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventPublisher {
    private final KafkaTemplate<String, PaymentCreatedEvent> kafkaTemplate;
    public PaymentEventPublisher(KafkaTemplate<String, PaymentCreatedEvent> kafkaTemplate) { this.kafkaTemplate = kafkaTemplate; }
    public void publish(PaymentCreatedEvent event) { kafkaTemplate.send("payments.created.v1", event.paymentId().toString(), event); }
}
