package com.charitha.payments.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class OutboxMetrics {
    private final Counter published;
    private final Counter failed;
    private final Timer publishLatency;

    public OutboxMetrics(MeterRegistry registry) {
        this.published = Counter.builder("payments.outbox.publish.events")
                .description("Outbox publish attempts completed successfully")
                .tag("outcome", "success")
                .register(registry);
        this.failed = Counter.builder("payments.outbox.publish.events")
                .description("Outbox publish attempts that failed")
                .tag("outcome", "failure")
                .register(registry);
        this.publishLatency = Timer.builder("payments.outbox.publish.latency")
                .description("Kafka publication latency for claimed outbox events")
                .publishPercentileHistogram()
                .register(registry);
    }

    public void recordSuccess(long elapsedNanos) {
        publishLatency.record(elapsedNanos, TimeUnit.NANOSECONDS);
        published.increment();
    }

    public void recordFailure(long elapsedNanos) {
        publishLatency.record(elapsedNanos, TimeUnit.NANOSECONDS);
        failed.increment();
    }
}
