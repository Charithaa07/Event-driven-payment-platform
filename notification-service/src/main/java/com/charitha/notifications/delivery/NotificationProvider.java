package com.charitha.notifications.delivery;

public interface NotificationProvider {
    DeliveryReceipt send(NotificationMessage message);
}
