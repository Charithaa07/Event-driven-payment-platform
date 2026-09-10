package com.charitha.payments.idempotency;

import com.charitha.payments.domain.Payment;
import com.charitha.payments.domain.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisIdempotencyStoreTest {
    @Test
    void redisFailureDoesNotBlockPaymentPath() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue())
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));
        RedisIdempotencyStore store = new RedisIdempotencyStore(
                redisTemplate,
                JsonMapper.builder().build(),
                24
        );
        Payment payment = new Payment(
                UUID.randomUUID(),
                "req-123",
                new BigDecimal("42.50"),
                "USD",
                "customer-1",
                PaymentStatus.ACCEPTED,
                Instant.parse("2026-09-10T17:00:00Z")
        );

        assertTrue(store.findPayment("customer-1", "req-123").isEmpty());
        assertDoesNotThrow(() -> store.put("customer-1", "req-123", payment));
        assertDoesNotThrow(() -> store.evict("customer-1", "req-123"));
    }
}
