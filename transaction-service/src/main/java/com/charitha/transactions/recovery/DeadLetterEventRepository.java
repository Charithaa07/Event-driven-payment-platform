package com.charitha.transactions.recovery;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, UUID> {

    List<DeadLetterEvent> findTop100ByStatusOrderByReceivedAtDesc(DeadLetterStatus status);

    @Modifying
    @Query(value = """
            INSERT INTO dead_letter_events (
                id, source_topic, dlt_topic, dlt_partition, dlt_offset,
                original_partition, original_offset, original_consumer_group,
                message_key, payload, failure_class, failure_message,
                status, received_at, replay_attempts
            ) VALUES (
                :id, :sourceTopic, :dltTopic, :dltPartition, :dltOffset,
                :originalPartition, :originalOffset, :originalConsumerGroup,
                :messageKey, :payload, :failureClass, :failureMessage,
                'PENDING', :receivedAt, 0
            )
            ON CONFLICT (dlt_topic, dlt_partition, dlt_offset) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("sourceTopic") String sourceTopic,
            @Param("dltTopic") String dltTopic,
            @Param("dltPartition") int dltPartition,
            @Param("dltOffset") long dltOffset,
            @Param("originalPartition") Integer originalPartition,
            @Param("originalOffset") Long originalOffset,
            @Param("originalConsumerGroup") String originalConsumerGroup,
            @Param("messageKey") String messageKey,
            @Param("payload") String payload,
            @Param("failureClass") String failureClass,
            @Param("failureMessage") String failureMessage,
            @Param("receivedAt") Instant receivedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE dead_letter_events
            SET status = 'REPLAYING',
                replay_claimed_at = CURRENT_TIMESTAMP,
                replay_attempts = replay_attempts + 1,
                replayed_by = :operator,
                last_replay_error = NULL
            WHERE id = :id
              AND (
                    status IN ('PENDING', 'FAILED')
                    OR (
                        status = 'REPLAYING'
                        AND replay_claimed_at < CURRENT_TIMESTAMP - INTERVAL '30 seconds'
                    )
              )
            """, nativeQuery = true)
    int claimForReplay(@Param("id") UUID id, @Param("operator") String operator);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE dead_letter_events
            SET status = 'REPLAYED',
                replayed_at = CURRENT_TIMESTAMP,
                replay_claimed_at = NULL,
                last_replay_error = NULL
            WHERE id = :id AND status = 'REPLAYING'
            """, nativeQuery = true)
    int markReplayed(@Param("id") UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE dead_letter_events
            SET status = 'FAILED',
                replay_claimed_at = NULL,
                last_replay_error = :error
            WHERE id = :id AND status = 'REPLAYING'
            """, nativeQuery = true)
    int markReplayFailed(@Param("id") UUID id, @Param("error") String error);
}
