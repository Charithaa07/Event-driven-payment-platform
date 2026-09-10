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
                message_key, payload, status, received_at, replay_attempts
            ) VALUES (
                :id, :sourceTopic, :dltTopic, :dltPartition, :dltOffset,
                :messageKey, :payload, 'PENDING', :receivedAt, 0
            )
            ON CONFLICT (dlt_topic, dlt_partition, dlt_offset) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("sourceTopic") String sourceTopic,
            @Param("dltTopic") String dltTopic,
            @Param("dltPartition") int dltPartition,
            @Param("dltOffset") long dltOffset,
            @Param("messageKey") String messageKey,
            @Param("payload") String payload,
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
