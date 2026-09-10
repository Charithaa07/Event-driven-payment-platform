package com.charitha.audit.api;

import java.time.Instant;

public record AuditApiError(
        int status,
        String error,
        String message,
        Instant timestamp
) {
}
