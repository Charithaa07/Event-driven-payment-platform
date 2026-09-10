package com.charitha.payments.idempotency;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisIdempotencyStoreTest {
    @Test
    void RedisFailureDoesNotBlockPaymentPath() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForValue())
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));
        RedisIdempotencyStore store = new RedisIdempotencyStore(redisTemplate, 24);

        assertTrue(store.findPaymentId("req-123").isEmpty());
        assertDoesNotThrow(() -> store.put("req-123", UUID.randomUUID()));
        assertDoesNotThrow(() -> store.evict("req-123"));
    }
}
