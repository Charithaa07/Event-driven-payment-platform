package com.charitha.transactions.recovery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dead_letter_events")
public class DeadLetterEvent {
    @Id
    private UUID id;

    @Column(name = "source_topic", nullable = false, length = 160)
    private String sourceTopic;

    @Column(name = "dlt_topic", nullable = false, length = 160)
    private String dltTopic;

    @Column(name = "dlt_partition", nullable = false)
    private int dltPartition;

    @Column(name = "dlt_offset", nullable = false)
    private long dltOffset;

    @Column(name = "message_key", length = 255)
    private String messageKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeadLetterStatus status;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "replay_claimed_at")
    private Instant replayClaimedAt;

    @Column(name = "replayed_at")
    private Instant replayedAt;

    @Column(name = "replay_attempts", nullable = false)
    private int replayAttempts;

    @Column(name = "replayed_by", length = 120)
    private String replayedBy;

    @Column(name = "last_replay_error", length = 1000)
    private String lastReplayError;

    protected DeadLetterEvent() {
    }

    public DeadLetterEvent(UUID id,
                           String sourceTopic,
                           String dltTopic,
                           int dltPartition,
                           long dltOffset,
                           String messageKey,
                           String payload,
                           Instant receivedAt) {
        this.id = id;
        this.sourceTopic = sourceTopic;
        this.dltTopic = dltTopic;
        this.dltPartition = dltPartition;
        this.dltOffset = dltOffset;
        this.messageKey = messageKey;
        this.payload = payload;
        this.status = DeadLetterStatus.PENDING;
        this.receivedAt = receivedAt;
        this.replayAttempts = 0;
    }

    public UUID getId() { return id; }
    public String getSourceTopic() { return sourceTopic; }
    public String getDltTopic() { return dltTopic; }
    public int getDltPartition() { return dltPartition; }
    public long getDltOffset() { return dltOffset; }
    public String getMessageKey() { return messageKey; }
    public String getPayload() { return payload; }
    public DeadLetterStatus getStatus() { return status; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getReplayClaimedAt() { return replayClaimedAt; }
    public Instant getReplayedAt() { return replayedAt; }
    public int getReplayAttempts() { return replayAttempts; }
    public String getReplayedBy() { return replayedBy; }
    public String getLastReplayError() { return lastReplayError; }
}
