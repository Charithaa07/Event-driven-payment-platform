package com.charitha.transactions.recovery;

import java.time.Instant;
import java.util.UUID;

public record DeadLetterEventDetail(
        UUID id,
        String sourceTopic,
        String dltTopic,
        int dltPartition,
        long dltOffset,
        Integer originalPartition,
        Long originalOffset,
        String originalConsumerGroup,
        String messageKey,
        String payload,
        String failureClass,
        String failureMessage,
        DeadLetterStatus status,
        int replayAttempts,
        Instant receivedAt,
        Instant replayedAt,
        String replayedBy,
        String lastReplayError
) {
    public static DeadLetterEventDetail from(DeadLetterEvent event) {
        return new DeadLetterEventDetail(
                event.getId(),
                event.getSourceTopic(),
                event.getDltTopic(),
                event.getDltPartition(),
                event.getDltOffset(),
                event.getOriginalPartition(),
                event.getOriginalOffset(),
                event.getOriginalConsumerGroup(),
                event.getMessageKey(),
                event.getPayload(),
                event.getFailureClass(),
                event.getFailureMessage(),
                event.getStatus(),
                event.getReplayAttempts(),
                event.getReceivedAt(),
                event.getReplayedAt(),
                event.getReplayedBy(),
                event.getLastReplayError()
        );
    }
}
