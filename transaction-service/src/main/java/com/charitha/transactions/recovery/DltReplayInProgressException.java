package com.charitha.transactions.recovery;

import java.util.UUID;

public class DltReplayInProgressException extends RuntimeException {
    public DltReplayInProgressException(UUID eventId) {
        super("Dead-letter replay is already in progress: " + eventId);
    }
}
