package com.charitha.transactions.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_transactions")
public class TransactionRecord {
    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false, unique = true)
    private UUID paymentId;

    @Column(name = "source_event_id", nullable = false, unique = true)
    private UUID sourceEventId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "customer_id", nullable = false, length = 120)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    @Column(name = "source_occurred_at", nullable = false)
    private Instant sourceOccurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TransactionRecord() {
    }

    public TransactionRecord(UUID id, UUID paymentId, UUID sourceEventId, BigDecimal amount,
                             String currency, String customerId, TransactionStatus status,
                             Instant sourceOccurredAt, Instant createdAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.sourceEventId = sourceEventId;
        this.amount = amount;
        this.currency = currency;
        this.customerId = customerId;
        this.status = status;
        this.sourceOccurredAt = sourceOccurredAt;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getPaymentId() { return paymentId; }
    public UUID getSourceEventId() { return sourceEventId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getCustomerId() { return customerId; }
    public TransactionStatus getStatus() { return status; }
    public Instant getSourceOccurredAt() { return sourceOccurredAt; }
    public Instant getCreatedAt() { return createdAt; }
}
