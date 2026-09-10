package com.charitha.payments.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Application error response")
public record ApiErrorResponse(
        @Schema(description = "UTC timestamp at which the error response was created", example = "2026-09-10T18:40:00Z")
        Instant timestamp,

        @Schema(description = "Human-readable error message", example = "Payment was not found")
        String error
) {
    static ApiErrorResponse from(RuntimeException ex) {
        return new ApiErrorResponse(Instant.now(), ex.getMessage());
    }
}
