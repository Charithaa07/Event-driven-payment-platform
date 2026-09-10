package com.charitha.notifications.api;

import com.charitha.notifications.domain.NotificationChannel;
import com.charitha.notifications.domain.NotificationDelivery;
import com.charitha.notifications.domain.NotificationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record NotificationView(
        UUID notificationId,
        UUID sourceEventId,
        UUID paymentId,
        String customerId,
        NotificationChannel channel,
        String template,
        BigDecimal amount,
        String currency,
        NotificationStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        String provider,
        String providerMessageId,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {

    public static NotificationView from(NotificationDelivery delivery) {
        return new NotificationView(
                delivery.getId(),
                delivery.getSourceEventId(),
                delivery.getPaymentId(),
                delivery.getCustomerId(),
                delivery.getChannel(),
                delivery.getTemplate(),
                delivery.getAmount(),
                delivery.getCurrency(),
                delivery.getStatus(),
                delivery.getAttemptCount(),
                delivery.getNextAttemptAt(),
                delivery.getProvider(),
                delivery.getProviderMessageId(),
                delivery.getLastError(),
                delivery.getCreatedAt(),
                delivery.getUpdatedAt()
        );
    }
}
