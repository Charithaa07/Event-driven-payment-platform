package com.charitha.transactions.recovery;

import java.util.UUID;

public class DeadLetterEventNotFoundException extends RuntimeException {
    public DeadLetterEventNotFoundException(UUID eventId) {
        super("Dead-letter event not found: " + eventId);
    }
}
