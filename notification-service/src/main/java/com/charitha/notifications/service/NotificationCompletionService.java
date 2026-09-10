package com.charitha.notifications.service;

import com.charitha.notifications.domain.NotificationDelivery;
import com.charitha.notifications.domain.NotificationDeliveryRepository;
import com.charitha.notifications.domain.NotificationStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class NotificationCompletionService {
    private final NotificationDeliveryRepository repository;
    private final int maxAttempts;
    private final long baseBackoffMs;
    private final long maxBackoffMs;

    public NotificationCompletionService(
            NotificationDeliveryRepository repository,
            @Value("${notifications.dispatch.max-attempts:4}") int maxAttempts,
            @Value("${notifications.dispatch.base-backoff-ms:5000}") long baseBackoffMs,
            @Value("${notifications.dispatch.max-backoff-ms:60000}") long maxBackoffMs) {
        this.repository = repository;
        this.maxAttempts = maxAttempts;
        this.baseBackoffMs = baseBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
    }

    @Transactional
    public void markSent(UUID notificationId, String provider, String providerMessageId, Instant now) {
        NotificationDelivery delivery = repository.findById(notificationId).orElseThrow();
        if (delivery.getStatus() == NotificationStatus.PROCESSING) {
            delivery.markSent(provider, providerMessageId, now);
        }
    }

    @Transactional
    public NotificationFailureDisposition markFailedAttempt(
            UUID notificationId,
            String provider,
            String error,
            boolean retryable,
            Instant now) {
        NotificationDelivery delivery = repository.findById(notificationId).orElseThrow();
        if (delivery.getStatus() != NotificationStatus.PROCESSING) {
            return delivery.getStatus() == NotificationStatus.RETRY_PENDING
                    ? NotificationFailureDisposition.RETRY_SCHEDULED
                    : NotificationFailureDisposition.FAILED;
        }

        String safeError = truncate(error);
        if (retryable && delivery.getAttemptCount() < maxAttempts) {
            long delayMs = backoffForAttempt(delivery.getAttemptCount());
            delivery.markRetry(provider, safeError, now.plusMillis(delayMs), now);
            return NotificationFailureDisposition.RETRY_SCHEDULED;
        }

        delivery.markFailed(provider, safeError, now);
        return NotificationFailureDisposition.FAILED;
    }

    private long backoffForAttempt(int attempt) {
        int exponent = Math.max(0, Math.min(attempt - 1, 20));
        long multiplier = 1L << exponent;
        long candidate;
        try {
            candidate = Math.multiplyExact(baseBackoffMs, multiplier);
        } catch (ArithmeticException ex) {
            candidate = maxBackoffMs;
        }
        return Math.min(candidate, maxBackoffMs);
    }

    private String truncate(String error) {
        String value = error == null || error.isBlank() ? "Provider delivery failed" : error;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
