package com.charitha.notifications.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_deliveries")
public class NotificationDelivery {
    @Id
    private UUID id;

    @Column(name = "source_event_id", nullable = false)
    private UUID sourceEventId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "customer_id", nullable = false, length = 120)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(name = "template", nullable = false, length = 80)
    private String template;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private NotificationStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "provider", length = 80)
    private String provider;

    @Column(name = "provider_message_id", length = 160)
    private String providerMessageId;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected NotificationDelivery() {
    }

    public void claim(Instant now) {
        status = NotificationStatus.PROCESSING;
        attemptCount++;
        processingStartedAt = now;
        updatedAt = now;
    }

    public void markSent(String provider, String providerMessageId, Instant now) {
        status = NotificationStatus.SENT;
        this.provider = provider;
        this.providerMessageId = providerMessageId;
        lastError = null;
        processingStartedAt = null;
        nextAttemptAt = null;
        updatedAt = now;
    }

    public void markRetry(String provider, String error, Instant retryAt, Instant now) {
        status = NotificationStatus.RETRY_PENDING;
        this.provider = provider;
        lastError = error;
        processingStartedAt = null;
        nextAttemptAt = retryAt;
        updatedAt = now;
    }

    public void markFailed(String provider, String error, Instant now) {
        status = NotificationStatus.FAILED;
        this.provider = provider;
        lastError = error;
        processingStartedAt = null;
        nextAttemptAt = null;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getSourceEventId() { return sourceEventId; }
    public UUID getPaymentId() { return paymentId; }
    public String getCustomerId() { return customerId; }
    public NotificationChannel getChannel() { return channel; }
    public String getTemplate() { return template; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public NotificationStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getProcessingStartedAt() { return processingStartedAt; }
    public String getProvider() { return provider; }
    public String getProviderMessageId() { return providerMessageId; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
