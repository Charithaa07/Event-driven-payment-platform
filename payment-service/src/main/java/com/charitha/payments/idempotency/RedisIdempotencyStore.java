package com.charitha.payments.idempotency;

import com.charitha.payments.domain.Payment;
import com.charitha.payments.domain.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Component
public class RedisIdempotencyStore {
    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyStore.class);
    private static final String KEY_PREFIX = "payments:idempotency:v2:";

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;
    private final Duration ttl;

    public RedisIdempotencyStore(StringRedisTemplate redisTemplate,
                                 JsonMapper jsonMapper,
                                 @Value("${idempotency.redis.ttl-hours:24}") long ttlHours) {
        this.redisTemplate = redisTemplate;
        this.jsonMapper = jsonMapper;
        this.ttl = Duration.ofHours(ttlHours);
    }

    public Optional<Payment> findPayment(String customerId, String idempotencyKey) {
        try {
            String value = redisTemplate.opsForValue().get(redisKey(customerId, idempotencyKey));
            if (value == null) {
                return Optional.empty();
            }

            CachedPayment cached = jsonMapper.readValue(value, CachedPayment.class);
            Payment payment = cached.toPayment();
            if (!idempotencyKey.equals(payment.getIdempotencyKey())
                    || !customerId.equals(payment.getCustomerId())) {
                throw new IllegalArgumentException("Cached idempotency scope mismatch");
            }
            return Optional.of(payment);
        } catch (DataAccessException ex) {
            log.warn("Redis idempotency lookup failed; falling back to PostgreSQL", ex);
            return Optional.empty();
        } catch (RuntimeException ex) {
            log.warn("Ignoring malformed Redis idempotency value");
            evict(customerId, idempotencyKey);
            return Optional.empty();
        }
    }

    public void put(String customerId, String idempotencyKey, Payment payment) {
        try {
            String value = jsonMapper.writeValueAsString(CachedPayment.from(payment));
            redisTemplate.opsForValue().set(redisKey(customerId, idempotencyKey), value, ttl);
        } catch (DataAccessException ex) {
            log.warn("Redis idempotency write failed; PostgreSQL remains authoritative", ex);
        } catch (JacksonException ex) {
            log.warn("Payment idempotency response could not be serialized for Redis", ex);
        }
    }

    public void evict(String customerId, String idempotencyKey) {
        try {
            redisTemplate.delete(redisKey(customerId, idempotencyKey));
        } catch (DataAccessException ex) {
            log.warn("Redis idempotency eviction failed", ex);
        }
    }

    static String redisKey(String customerId, String idempotencyKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] scopedKey = (customerId + "\u0000" + idempotencyKey).getBytes(StandardCharsets.UTF_8);
            return KEY_PREFIX + HexFormat.of().formatHex(digest.digest(scopedKey));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private record CachedPayment(
            String id,
            String idempotencyKey,
            String amount,
            String currency,
            String customerId,
            String status,
            String createdAt
    ) {
        static CachedPayment from(Payment payment) {
            return new CachedPayment(
                    payment.getId().toString(),
                    payment.getIdempotencyKey(),
                    payment.getAmount().toPlainString(),
                    payment.getCurrency(),
                    payment.getCustomerId(),
                    payment.getStatus().name(),
                    payment.getCreatedAt().toString()
            );
        }

        Payment toPayment() {
            return new Payment(
                    UUID.fromString(id),
                    idempotencyKey,
                    new BigDecimal(amount),
                    currency,
                    customerId,
                    PaymentStatus.valueOf(status),
                    Instant.parse(createdAt)
            );
        }
    }
}
