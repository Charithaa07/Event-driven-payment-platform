package com.charitha.payments.observability;

import com.charitha.payments.outbox.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

@Component
public class OutboxBacklogMetrics implements MeterBinder {
    private final OutboxEventRepository repository;

    public OutboxBacklogMetrics(OutboxEventRepository repository) {
        this.repository = repository;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        registerGauge(registry, "pending", "PENDING");
        registerGauge(registry, "processing", "PROCESSING");
        registerGauge(registry, "failed", "FAILED");
    }

    private void registerGauge(MeterRegistry registry, String metricStatus, String persistedStatus) {
        Gauge.builder("payments.outbox.events", repository, repo -> repo.countByStatus(persistedStatus))
                .description("Current number of outbox events by delivery status")
                .tag("status", metricStatus)
                .register(registry);
    }
}
