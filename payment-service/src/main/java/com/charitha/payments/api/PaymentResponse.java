package com.charitha.payments.api;

import com.charitha.payments.domain.Payment;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Accepted payment resource")
public record PaymentResponse(
        @Schema(description = "Payment identifier", example = "6d834f55-9d6d-4ca5-873f-92327fd582c2")
        UUID id,

        @Schema(description = "Accepted payment amount", example = "42.50")
        BigDecimal amount,

        @Schema(description = "Currency code", example = "USD")
        String currency,

        @Schema(description = "Authenticated customer identifier derived from JWT subject", example = "customer-123")
        String customerId,

        @Schema(description = "Current payment status", example = "ACCEPTED")
        String status,

        @Schema(description = "UTC creation timestamp", example = "2026-09-10T18:40:00Z")
        Instant createdAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getCustomerId(),
                payment.getStatus().name(),
                payment.getCreatedAt()
        );
    }
}
