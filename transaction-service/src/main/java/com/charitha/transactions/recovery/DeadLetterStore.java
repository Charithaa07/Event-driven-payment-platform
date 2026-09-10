package com.charitha.transactions.recovery;

import com.charitha.transactions.observability.TransactionConsumerMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
    public void index(String configuredSourceTopic, ConsumerRecord<String, String> record) {
        String headerSourceTopic = stringHeader(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        String failureClass = firstNonBlank(
                stringHeader(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN),
                stringHeader(record, KafkaHeaders.DLT_EXCEPTION_FQCN)
        );

        int inserted = repository.insertIfAbsent(
                UUID.randomUUID(),
                firstNonBlank(headerSourceTopic, configuredSourceTopic),
                record.topic(),
                record.partition(),
                record.offset(),
                intHeader(record, KafkaHeaders.DLT_ORIGINAL_PARTITION),
                longHeader(record, KafkaHeaders.DLT_ORIGINAL_OFFSET),
                truncate(stringHeader(record, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP), 255),
                truncate(record.key(), 255),
                record.value(),
                truncate(failureClass, 500),
                truncate(stringHeader(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE), 1000),
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
        int updated = repository.markReplayed(eventId);
        if (updated != 1) {
            throw new IllegalStateException("Dead-letter event was not in REPLAYING state: " + eventId);
        }
        return get(eventId);
    }

    @Transactional
    public DeadLetterEvent markReplayFailed(UUID eventId, Throwable failure) {
        int updated = repository.markReplayFailed(eventId, failureMessage(failure));
        if (updated != 1) {
            throw new IllegalStateException("Dead-letter replay failure could not be recorded: " + eventId);
        }
        return get(eventId);
    }

    private String validateOperator(String operator) {
        if (operator == null || operator.isBlank() || operator.length() > 120) {
            throw new IllegalArgumentException("Authenticated operator subject is invalid");
        }
        return operator;
    }

    private String stringHeader(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) {
            return null;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private Integer intHeader(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null || header.value().length != Integer.BYTES) {
            return null;
        }
        return ByteBuffer.wrap(header.value()).getInt();
    }

    private Long longHeader(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null || header.value().length != Long.BYTES) {
            return null;
        }
        return ByteBuffer.wrap(header.value()).getLong();
    }

    private String firstNonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
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
