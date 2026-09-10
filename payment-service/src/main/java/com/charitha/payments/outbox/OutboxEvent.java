package com.charitha.payments.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 60)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 120)
    private String eventType;

    @Column(nullable = false, length = 160)
    private String topic;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    protected OutboxEvent() {
    }

    public OutboxEvent(UUID id, String aggregateType, UUID aggregateId, String eventType,
                       String topic, String payload, Instant createdAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
        this.status = "PENDING";
        this.attempts = 0;
        this.createdAt = createdAt;
        this.nextAttemptAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getTopic() { return topic; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getClaimedAt() { return claimedAt; }
    public String getLastError() { return lastError; }

    public void claim(Instant claimedAt) {
        this.status = "PROCESSING";
        this.claimedAt = claimedAt;
        this.attempts++;
    }

    public void markPublished(Instant publishedAt) {
        this.status = "PUBLISHED";
        this.publishedAt = publishedAt;
        this.claimedAt = null;
        this.lastError = null;
    }

    public void recordFailure(Instant nextAttemptAt, String lastError, boolean terminal) {
        this.status = terminal ? "FAILED" : "PENDING";
        this.nextAttemptAt = nextAttemptAt;
        this.claimedAt = null;
        this.lastError = lastError;
    }
}
