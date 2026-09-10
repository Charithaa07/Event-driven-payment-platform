package com.charitha.notifications.service;

import com.charitha.notifications.delivery.DeliveryReceipt;
import com.charitha.notifications.delivery.NotificationMessage;
import com.charitha.notifications.delivery.NotificationProvider;
import com.charitha.notifications.delivery.NotificationProviderException;
import com.charitha.notifications.observability.NotificationMetrics;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class NotificationDispatcher {
    private final NotificationClaimService claimService;
    private final NotificationCompletionService completionService;
    private final NotificationProvider provider;
    private final NotificationMetrics metrics;

    public NotificationDispatcher(
            NotificationClaimService claimService,
            NotificationCompletionService completionService,
            NotificationProvider provider,
            NotificationMetrics metrics) {
        this.claimService = claimService;
        this.completionService = completionService;
        this.provider = provider;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${notifications.dispatch.fixed-delay-ms:1000}")
    public void dispatchDueNotifications() {
        List<ClaimedNotification> claimed = claimService.claimDue(Instant.now());
        for (ClaimedNotification notification : claimed) {
            deliver(notification);
        }
    }

    private void deliver(ClaimedNotification notification) {
        NotificationMessage message = new NotificationMessage(
                notification.notificationId(),
                notification.paymentId(),
                notification.customerId(),
                notification.template(),
                notification.amount(),
                notification.currency(),
                notification.attemptNumber()
        );

        try {
            DeliveryReceipt receipt = provider.send(message);
            completionService.markSent(
                    notification.notificationId(),
                    receipt.provider(),
                    receipt.providerMessageId(),
                    Instant.now()
            );
            metrics.recordDelivery("sent");
        } catch (NotificationProviderException ex) {
            recordFailure(notification, ex.getMessage(), ex.isRetryable());
        } catch (RuntimeException ex) {
            recordFailure(notification, ex.getMessage(), true);
        }
    }

    private void recordFailure(ClaimedNotification notification, String error, boolean retryable) {
        NotificationFailureDisposition disposition = completionService.markFailedAttempt(
                notification.notificationId(),
                provider.providerName(),
                error,
                retryable,
                Instant.now()
        );
        metrics.recordDelivery(disposition == NotificationFailureDisposition.RETRY_SCHEDULED
                ? "retry_scheduled"
                : "failed");
    }
}
