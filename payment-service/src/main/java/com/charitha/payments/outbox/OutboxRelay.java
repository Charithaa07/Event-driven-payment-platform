package com.charitha.payments.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@ConditionalOnProperty(prefix = "outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxClaimService claimService;
    private final OutboxDeliveryService deliveryService;
    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final long publishTimeoutMs;

    public OutboxRelay(
            OutboxClaimService claimService,
            OutboxDeliveryService deliveryService,
            KafkaTemplate<Object, Object> kafkaTemplate,
            @Value("${outbox.relay.publish-timeout-ms:10000}") long publishTimeoutMs) {
        this.claimService = claimService;
        this.deliveryService = deliveryService;
        this.kafkaTemplate = kafkaTemplate;
        this.publishTimeoutMs = publishTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${outbox.relay.fixed-delay-ms:1000}")
    public void publishPendingEvents() {
        for (OutboxMessage event : claimService.claimBatch()) {
            try {
                kafkaTemplate
                        .send(event.topic(), event.aggregateId().toString(), event.payload())
                        .get(publishTimeoutMs, TimeUnit.MILLISECONDS);
                deliveryService.markPublished(event.id());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                recordFailure(event, ex);
                return;
            } catch (ExecutionException | TimeoutException | RuntimeException ex) {
                recordFailure(event, ex);
            }
        }
    }

    private void recordFailure(OutboxMessage event, Throwable failure) {
        log.warn(
                "Outbox publish failed for event {} on topic {}: {}",
                event.id(),
                event.topic(),
                failure.getMessage()
        );
        deliveryService.markDeliveryFailure(event.id(), failure);
    }
}
