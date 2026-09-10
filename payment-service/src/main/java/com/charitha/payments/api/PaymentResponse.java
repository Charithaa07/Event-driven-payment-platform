package com.charitha.payments.api;

import com.charitha.payments.domain.Payment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(UUID id, BigDecimal amount, String currency, String customerId, String status, Instant createdAt) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getAmount(), payment.getCurrency(), payment.getCustomerId(), payment.getStatus().name(), payment.getCreatedAt());
    }
}
