package com.veda.payments.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {
    @Id private UUID id;
    @Column(nullable = false, unique = true, length = 100) private String idempotencyKey;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) private String currency;
    @Column(nullable = false, length = 120) private String customerId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private PaymentStatus status;
    @Column(nullable = false) private Instant createdAt;

    protected Payment() {}
    public Payment(UUID id, String idempotencyKey, BigDecimal amount, String currency, String customerId, PaymentStatus status, Instant createdAt) {
        this.id = id; this.idempotencyKey = idempotencyKey; this.amount = amount; this.currency = currency; this.customerId = customerId; this.status = status; this.createdAt = createdAt;
    }
    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getCustomerId() { return customerId; }
    public PaymentStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
