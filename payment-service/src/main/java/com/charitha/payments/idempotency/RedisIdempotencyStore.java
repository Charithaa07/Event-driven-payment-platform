package com.charitha.payments.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Component
public class RedisIdempotencyStore {
    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyStore.class);
    private static final String KEY_PREFIX = "payments:idempotency:";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public RedisIdempotencyStore(StringRedisTemplate redisTemplate,
                                 @Value("${idempotency.redis.ttl-hours:24}") long ttlHours) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofHours(ttlHours);
    }

    public Optional<UUID> findPaymentId(String idempotencyKey) {
        try {
            String value = redisTemplate.opsForValue().get(redisKey(idempotencyKey));
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            log.warn("Ignoring malformed Redis idempotency value for key {}", idempotencyKey);
            evict(idempotencyKey);
            return Optional.empty();
        } catch (DataAccessException ex) {
            log.warn("Redis idempotency lookup failed; falling back to PostgreSQL", ex);
            return Optional.empty();
        }
    }

    public void put(String idempotencyKey, UUID paymentId) {
        try {
            redisTemplate.opsForValue().set(redisKey(idempotencyKey), paymentId.toString(), ttl);
        } catch (DataAccessException ex) {
            log.warn("Redis idempotency write failed; PostgreSQL remains authoritative", ex);
        }
    }

    public void evict(String idempotencyKey) {
        try {
            redisTemplate.delete(redisKey(idempotencyKey));
        } catch (DataAccessException ex) {
            log.warn("Redis idempotency eviction failed", ex);
        }
    }

    static String redisKey(String idempotencyKey) {
        return KEY_PREFIX + idempotencyKey;
    }
}
