package com.charitha.transactions.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class TransactionConsumerMetrics {
    private final Counter received;
    private final Counter created;
    private final Counter duplicate;
    private final Counter malformed;
    private final Counter dltPublished;
    private final Timer createdLatency;
    private final Timer duplicateLatency;

    public TransactionConsumerMetrics(MeterRegistry registry) {
        this.received = eventCounter(registry, "received");
        this.created = eventCounter(registry, "created");
        this.duplicate = eventCounter(registry, "duplicate");
        this.malformed = eventCounter(registry, "malformed");
        this.dltPublished = Counter.builder("transactions.kafka.dlt")
                .description("Payment events published to the dead-letter topic")
                .register(registry);
        this.createdLatency = processingTimer(registry, "created");
        this.duplicateLatency = processingTimer(registry, "duplicate");
    }

    public void recordReceived() {
        received.increment();
    }

    public void recordProcessed(boolean transactionCreated, long elapsedNanos) {
        if (transactionCreated) {
            created.increment();
            createdLatency.record(elapsedNanos, TimeUnit.NANOSECONDS);
        } else {
            duplicate.increment();
            duplicateLatency.record(elapsedNanos, TimeUnit.NANOSECONDS);
        }
    }

    public void recordMalformed() {
        malformed.increment();
    }

    public void recordDltPublished() {
        dltPublished.increment();
    }

    private Counter eventCounter(MeterRegistry registry, String outcome) {
        return Counter.builder("transactions.payment.events")
                .description("Payment-created events observed by Transaction Service")
                .tag("outcome", outcome)
                .register(registry);
    }

    private Timer processingTimer(MeterRegistry registry, String outcome) {
        return Timer.builder("transactions.payment.processing.latency")
                .description("Transaction Service processing latency for payment-created events")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(registry);
    }
}
