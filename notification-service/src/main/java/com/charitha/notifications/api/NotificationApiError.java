package com.charitha.notifications.api;

import java.time.Instant;

public record NotificationApiError(String code, String message, Instant timestamp) {
}
