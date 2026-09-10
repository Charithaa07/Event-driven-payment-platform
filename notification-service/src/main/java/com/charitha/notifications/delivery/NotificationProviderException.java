package com.charitha.notifications.delivery;

public class NotificationProviderException extends RuntimeException {
    private final boolean retryable;

    public NotificationProviderException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public NotificationProviderException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
