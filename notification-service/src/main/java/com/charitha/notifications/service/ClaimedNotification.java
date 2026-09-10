package com.charitha.notifications.service;

import com.charitha.notifications.domain.NotificationDelivery;

import java.math.BigDecimal;
import java.util.UUID;

public record ClaimedNotification(
        UUID notificationId,
        UUID sourceEventId,
        UUID paymentId,
        String customerId,
        String template,
        BigDecimal amount,
        String currency,
        int attemptNumber) {

    static ClaimedNotification from(NotificationDelivery delivery) {
        return new ClaimedNotification(
                delivery.getId(),
                delivery.getSourceEventId(),
                delivery.getPaymentId(),
                delivery.getCustomerId(),
                delivery.getTemplate(),
                delivery.getAmount(),
                delivery.getCurrency(),
                delivery.getAttemptCount()
        );
    }
}
