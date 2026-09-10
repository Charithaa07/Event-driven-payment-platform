package com.charitha.transactions.recovery;

import com.charitha.transactions.observability.TransactionConsumerMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class DltReplayService {
    private final DeadLetterStore store;
    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final TransactionConsumerMetrics metrics;
    private final long publishTimeoutMs;

    public DltReplayService(
            DeadLetterStore store,
            KafkaTemplate<Object, Object> kafkaTemplate,
            TransactionConsumerMetrics metrics,
            @Value("${dlt.replay.publish-timeout-ms:10000}") long publishTimeoutMs) {
        this.store = store;
        this.kafkaTemplate = kafkaTemplate;
        this.metrics = metrics;
        this.publishTimeoutMs = publishTimeoutMs;
    }

    public DeadLetterEvent replay(UUID eventId, String operator) {
        DeadLetterEvent current = store.get(eventId);
        if (current.getStatus() == DeadLetterStatus.REPLAYED) {
            return current;
        }

        DeadLetterEvent claimed = store.claimForReplay(eventId, operator);
        if (claimed.getStatus() == DeadLetterStatus.REPLAYED) {
            return claimed;
        }

        try {
            kafkaTemplate
                    .send(claimed.getSourceTopic(), claimed.getMessageKey(), claimed.getPayload())
                    .get(publishTimeoutMs, TimeUnit.MILLISECONDS);
            DeadLetterEvent replayed = store.markReplayed(eventId);
            metrics.recordDltReplay(true);
            return replayed;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            store.markReplayFailed(eventId, ex);
            metrics.recordDltReplay(false);
            throw new DltReplayFailedException(eventId, ex);
        } catch (ExecutionException | TimeoutException | RuntimeException ex) {
            store.markReplayFailed(eventId, ex);
            metrics.recordDltReplay(false);
            throw new DltReplayFailedException(eventId, ex);
        }
    }
}
