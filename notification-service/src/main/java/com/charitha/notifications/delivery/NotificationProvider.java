package com.charitha.notifications.delivery;

public interface NotificationProvider {
    String providerName();

    DeliveryReceipt send(NotificationMessage message);
}
