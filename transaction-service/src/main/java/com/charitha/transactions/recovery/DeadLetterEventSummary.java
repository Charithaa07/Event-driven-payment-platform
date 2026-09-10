package com.charitha.transactions.recovery;

import java.time.Instant;
import java.util.UUID;

public record DeadLetterEventSummary(
        UUID id,
        String sourceTopic,
        String dltTopic,
        int dltPartition,
        long dltOffset,
        Integer originalPartition,
        Long originalOffset,
        String messageKey,
        String failureClass,
        String failureMessage,
        DeadLetterStatus status,
        int replayAttempts,
        Instant receivedAt,
        Instant replayedAt,
        String replayedBy,
        String lastReplayError
) {
    public static DeadLetterEventSummary from(DeadLetterEvent event) {
        return new DeadLetterEventSummary(
                event.getId(),
                event.getSourceTopic(),
                event.getDltTopic(),
                event.getDltPartition(),
                event.getDltOffset(),
                event.getOriginalPartition(),
                event.getOriginalOffset(),
                event.getMessageKey(),
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
