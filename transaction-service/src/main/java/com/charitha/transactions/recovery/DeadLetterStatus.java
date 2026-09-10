package com.charitha.transactions.recovery;

public enum DeadLetterStatus {
    PENDING,
    REPLAYING,
    REPLAYED,
    FAILED
}
