package com.charitha.notifications.delivery;

import java.math.BigDecimal;
import java.util.UUID;

public record NotificationMessage(
        UUID idempotencyKey,
        UUID paymentId,
        String customerReference,
        String template,
        BigDecimal amount,
        String currency,
        int attemptNumber) {
}
