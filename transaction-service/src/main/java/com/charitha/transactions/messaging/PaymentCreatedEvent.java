package com.charitha.transactions.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentCreatedEvent(
        UUID eventId,
        UUID paymentId,
        BigDecimal amount,
        String currency,
        String customerId,
        Instant occurredAt
) {}
