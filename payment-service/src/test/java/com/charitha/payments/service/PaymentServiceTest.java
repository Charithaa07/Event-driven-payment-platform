package com.charitha.payments.service;

import com.charitha.payments.api.CreatePaymentRequest;
import com.charitha.payments.domain.Payment;
import com.charitha.payments.domain.PaymentRepository;
import com.charitha.payments.idempotency.RedisIdempotencyStore;
import com.charitha.payments.outbox.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PaymentServiceTest {
    @Test
    void cachedIdempotencyKeyBypassesDurableKeyLookup() {
        PaymentRepository repository = mock(PaymentRepository.class);
        OutboxEventRepository outboxRepository = mock(OutboxEventRepository.class);
        JsonMapper jsonMapper = mock(JsonMapper.class);
        RedisIdempotencyStore idempotencyStore = mock(RedisIdempotencyStore.class);
        Payment existing = mock(Payment.class);
        UUID paymentId = UUID.randomUUID();

        when(idempotencyStore.findPaymentId("req-123")).thenReturn(Optional.of(paymentId));
        when(repository.findById(paymentId)).thenReturn(Optional.of(existing));

        PaymentService service = new PaymentService(repository, outboxRepository, jsonMapper, idempotencyStore);
        Payment result = service.create("req-123", request());

        assertEquals(existing, result);
        verify(repository, never()).findByIdempotencyKey(anyString());
        verify(repository, never()).save(any());
        verify(idempotencyStore, never()).put(anyString(), any());
        verifyNoInteractions(outboxRepository, jsonMapper);
    }

    @Test
    void databaseFallbackWarmsRedisForRepeatedIdempotencyKey() {
        PaymentRepository repository = mock(PaymentRepository.class);
        OutboxEventRepository outboxRepository = mock(OutboxEventRepository.class);
        JsonMapper jsonMapper = mock(JsonMapper.class);
        RedisIdempotencyStore idempotencyStore = mock(RedisIdempotencyStore.class);
        Payment existing = mock(Payment.class);
        UUID paymentId = UUID.randomUUID();

        when(idempotencyStore.findPaymentId("req-123")).thenReturn(Optional.empty());
        when(repository.findByIdempotencyKey("req-123")).thenReturn(Optional.of(existing));
        when(existing.getId()).thenReturn(paymentId);

        PaymentService service = new PaymentService(repository, outboxRepository, jsonMapper, idempotencyStore);
        Payment result = service.create("req-123", request());

        assertEquals(existing, result);
        verify(idempotencyStore).put("req-123", paymentId);
        verify(repository, never()).save(any());
        verifyNoInteractions(outboxRepository, jsonMapper);
    }

    @Test
    void staleRedisEntryIsEvictedBeforeDatabaseFallback() {
        PaymentRepository repository = mock(PaymentRepository.class);
        OutboxEventRepository outboxRepository = mock(OutboxEventRepository.class);
        JsonMapper jsonMapper = mock(JsonMapper.class);
        RedisIdempotencyStore idempotencyStore = mock(RedisIdempotencyStore.class);
        Payment existing = mock(Payment.class);
        UUID stalePaymentId = UUID.randomUUID();
        UUID actualPaymentId = UUID.randomUUID();

        when(idempotencyStore.findPaymentId("req-123")).thenReturn(Optional.of(stalePaymentId));
        when(repository.findById(stalePaymentId)).thenReturn(Optional.empty());
        when(repository.findByIdempotencyKey("req-123")).thenReturn(Optional.of(existing));
        when(existing.getId()).thenReturn(actualPaymentId);

        PaymentService service = new PaymentService(repository, outboxRepository, jsonMapper, idempotencyStore);
        Payment result = service.create("req-123", request());

        assertEquals(existing, result);
        verify(idempotencyStore).evict("req-123");
        verify(idempotencyStore).put("req-123", actualPaymentId);
        verify(repository, never()).save(any());
        verifyNoInteractions(outboxRepository, jsonMapper);
    }

    private CreatePaymentRequest request() {
        return new CreatePaymentRequest(new BigDecimal("42.50"), "USD", "customer-1");
    }
}
