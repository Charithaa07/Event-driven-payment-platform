package com.charitha.notifications.service;

import java.util.UUID;

public class NotificationNotFoundException extends RuntimeException {
    public NotificationNotFoundException(UUID notificationId) {
        super("Notification delivery not found: " + notificationId);
    }
}
