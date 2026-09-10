package com.charitha.transactions.recovery;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class DeadLetterRecoveryMetrics {

    public DeadLetterRecoveryMetrics(MeterRegistry registry,
                                     DeadLetterEventRepository repository) {
        Gauge.builder(
                        "transactions.kafka.dlt.backlog",
                        repository,
                        repo -> repo.countByStatus(DeadLetterStatus.PENDING)
                                + repo.countByStatus(DeadLetterStatus.FAILED)
                )
                .description("Dead-letter records waiting for operational recovery")
                .register(registry);
    }
}
