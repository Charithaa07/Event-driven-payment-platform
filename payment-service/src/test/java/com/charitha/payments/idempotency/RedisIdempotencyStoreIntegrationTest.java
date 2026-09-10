package com.charitha.payments.idempotency;

import com.charitha.payments.domain.Payment;
import com.charitha.payments.domain.PaymentStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class RedisIdempotencyStoreIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static JsonMapper jsonMapper;

    @BeforeAll
    static void setUpRedisClient() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        jsonMapper = JsonMapper.builder().build();
    }

    @AfterAll
    static void closeRedisClient() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void storesCompletePaymentResponseWithTtlAndReadsItBack() {
        RedisIdempotencyStore store = new RedisIdempotencyStore(redisTemplate, jsonMapper, 1);
        String customerId = "customer-1";
        String idempotencyKey = "checkout-redis-1";
        Payment payment = new Payment(
                UUID.randomUUID(),
                idempotencyKey,
                new BigDecimal("42.50"),
                "USD",
                customerId,
                PaymentStatus.ACCEPTED,
                Instant.parse("2026-09-10T17:00:00Z")
        );

        store.put(customerId, idempotencyKey, payment);

        Payment cached = store.findPayment(customerId, idempotencyKey).orElseThrow();
        assertEquals(payment.getId(), cached.getId());
        assertEquals(payment.getIdempotencyKey(), cached.getIdempotencyKey());
        assertEquals(payment.getAmount(), cached.getAmount());
        assertEquals(payment.getCurrency(), cached.getCurrency());
        assertEquals(payment.getCustomerId(), cached.getCustomerId());
        assertEquals(payment.getStatus(), cached.getStatus());
        assertEquals(payment.getCreatedAt(), cached.getCreatedAt());

        Long ttlSeconds = redisTemplate.getExpire(
                RedisIdempotencyStore.redisKey(customerId, idempotencyKey),
                TimeUnit.SECONDS
        );
        assertNotNull(ttlSeconds);
        assertTrue(ttlSeconds > 0 && ttlSeconds <= 3600);
    }

    @Test
    void malformedCachedValueIsEvictedAndTreatedAsMiss() {
        RedisIdempotencyStore store = new RedisIdempotencyStore(redisTemplate, jsonMapper, 1);
        String customerId = "customer-1";
        String idempotencyKey = "checkout-malformed";
        String redisKey = RedisIdempotencyStore.redisKey(customerId, idempotencyKey);
        redisTemplate.opsForValue().set(redisKey, "not-json");

        assertTrue(store.findPayment(customerId, idempotencyKey).isEmpty());
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(redisKey)));
    }

    @Test
    void sameIdempotencyKeyUsesDifferentCacheKeysForDifferentCustomers() {
        String sharedKey = "checkout-shared";
        assertNotEquals(
                RedisIdempotencyStore.redisKey("customer-a", sharedKey),
                RedisIdempotencyStore.redisKey("customer-b", sharedKey)
        );
    }
}
