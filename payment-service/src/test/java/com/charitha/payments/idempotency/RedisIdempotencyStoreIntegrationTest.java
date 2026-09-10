package com.charitha.payments.idempotency;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class RedisIdempotencyStoreIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void setUpRedisClient() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void closeRedisClient() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void storesPaymentIdWithTtlAndReadsItBack() {
        RedisIdempotencyStore store = new RedisIdempotencyStore(redisTemplate, 1);
        String idempotencyKey = "checkout-redis-1";
        UUID paymentId = UUID.randomUUID();

        store.put(idempotencyKey, paymentId);

        assertEquals(paymentId, store.findPaymentId(idempotencyKey).orElseThrow());
        Long ttlSeconds = redisTemplate.getExpire(
                RedisIdempotencyStore.redisKey(idempotencyKey),
                TimeUnit.SECONDS
        );
        assertNotNull(ttlSeconds);
        assertTrue(ttlSeconds > 0 && ttlSeconds <= 3600);
    }

    @Test
    void malformedCachedValueIsEvictedAndTreatedAsMiss() {
        RedisIdempotencyStore store = new RedisIdempotencyStore(redisTemplate, 1);
        String idempotencyKey = "checkout-malformed";
        String redisKey = RedisIdempotencyStore.redisKey(idempotencyKey);
        redisTemplate.opsForValue().set(redisKey, "not-a-payment-id");

        assertTrue(store.findPaymentId(idempotencyKey).isEmpty());
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey(redisKey)));
    }
}
