package com.charitha.transactions.recovery;

import com.charitha.transactions.observability.TransactionConsumerMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DeadLetterStore {
    private final DeadLetterEventRepository repository;
    private final TransactionConsumerMetrics metrics;

    public DeadLetterStore(DeadLetterEventRepository repository,
                           TransactionConsumerMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    @Transactional
    public void index(String sourceTopic, ConsumerRecord<String, String> record) {
        int inserted = repository.insertIfAbsent(
                UUID.randomUUID(),
                sourceTopic,
                record.topic(),
                record.partition(),
                record.offset(),
                truncate(record.key(), 255),
                record.value(),
                Instant.now()
        );
        if (inserted == 1) {
            metrics.recordDltIndexed();
        }
    }

    @Transactional(readOnly = true)
    public List<DeadLetterEvent> list(DeadLetterStatus status) {
        return repository.findTop100ByStatusOrderByReceivedAtDesc(status);
    }

    @Transactional(readOnly = true)
    public DeadLetterEvent get(UUID eventId) {
        return repository.findById(eventId)
                .orElseThrow(() -> new DeadLetterEventNotFoundException(eventId));
    }

    @Transactional
    public DeadLetterEvent claimForReplay(UUID eventId, String operator) {
        DeadLetterEvent current = get(eventId);
        if (current.getStatus() == DeadLetterStatus.REPLAYED) {
            return current;
        }

        int claimed = repository.claimForReplay(eventId, validateOperator(operator));
        if (claimed == 0) {
            DeadLetterEvent latest = get(eventId);
            if (latest.getStatus() == DeadLetterStatus.REPLAYED) {
                return latest;
            }
            throw new DltReplayInProgressException(eventId);
        }
        return get(eventId);
    }

    @Transactional
    public DeadLetterEvent markReplayed(UUID eventId) {
        repository.markReplayed(eventId);
        return get(eventId);
    }

    @Transactional
    public DeadLetterEvent markReplayFailed(UUID eventId, Throwable failure) {
        repository.markReplayFailed(eventId, failureMessage(failure));
        return get(eventId);
    }

    private String validateOperator(String operator) {
        if (operator == null || operator.isBlank() || operator.length() > 120) {
            throw new IllegalArgumentException("Authenticated operator subject is invalid");
        }
        return operator;
    }

    private String failureMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        return truncate(message, 1000);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
