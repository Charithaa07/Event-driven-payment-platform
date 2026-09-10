package com.charitha.notifications.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class NotificationMetrics {
    private final MeterRegistry registry;

    public NotificationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordIngestion(String outcome) {
        registry.counter("notifications.ingestion.events", "outcome", outcome).increment();
    }

    public void recordDelivery(String outcome) {
        registry.counter("notifications.delivery.attempts", "outcome", outcome).increment();
    }
}
