package com.charitha.audit.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class AuditMetrics {
    private final Counter received;
    private final Counter stored;
    private final Counter duplicate;
    private final Counter malformed;
    private final Counter deadLettered;

    public AuditMetrics(MeterRegistry registry) {
        this.received = counter(registry, "received");
        this.stored = counter(registry, "stored");
        this.duplicate = counter(registry, "duplicate");
        this.malformed = counter(registry, "malformed");
        this.deadLettered = counter(registry, "dead_lettered");
    }

    public void recordReceived() { received.increment(); }
    public void recordStored() { stored.increment(); }
    public void recordDuplicate() { duplicate.increment(); }
    public void recordMalformed() { malformed.increment(); }
    public void recordDeadLettered() { deadLettered.increment(); }

    private Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder("audit.payment.events")
                .description("Payment-created events observed by Audit Service")
                .tag("outcome", outcome)
                .register(registry);
    }
}
