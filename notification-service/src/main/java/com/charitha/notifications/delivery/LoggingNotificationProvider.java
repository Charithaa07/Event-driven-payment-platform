package com.charitha.notifications.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingNotificationProvider implements NotificationProvider {
    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationProvider.class);

    @Override
    public DeliveryReceipt send(NotificationMessage message) {
        String providerMessageId = "log-" + message.idempotencyKey();
        log.info(
                "Simulated notification delivery notificationId={} paymentId={} template={} attempt={}",
                message.idempotencyKey(),
                message.paymentId(),
                message.template(),
                message.attemptNumber()
        );
        return new DeliveryReceipt("logging", providerMessageId);
    }
}
