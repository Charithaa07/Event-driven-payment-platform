package com.charitha.transactions.recovery;

import java.util.UUID;

public class DltReplayFailedException extends RuntimeException {
    public DltReplayFailedException(UUID eventId, Throwable cause) {
        super("Failed to replay dead-letter event: " + eventId, cause);
    }
}
