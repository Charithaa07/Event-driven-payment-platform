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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymentServiceTest {
    @Test
    void cachedIdempotencyResponseBypassesPostgres() {
        PaymentRepository repository = mock(PaymentRepository.class);
        OutboxEventRepository outboxRepository = mock(OutboxEventRepository.class);
        JsonMapper jsonMapper = mock(JsonMapper.class);
        RedisIdempotencyStore idempotencyStore = mock(RedisIdempotencyStore.class);
        Payment existing = mock(Payment.class);

        when(idempotencyStore.findPayment("req-123")).thenReturn(Optional.of(existing));

        PaymentService service = new PaymentService(repository, outboxRepository, jsonMapper, idempotencyStore);
        Payment result = service.create("req-123", request());

        assertEquals(existing, result);
        verifyNoInteractions(repository, outboxRepository, jsonMapper);
        verify(idempotencyStore, never()).put(any(), any());
    }

    @Test
    void postgresFallbackWarmsRedisForRepeatedIdempotencyKey() {
        PaymentRepository repository = mock(PaymentRepository.class);
        OutboxEventRepository outboxRepository = mock(OutboxEventRepository.class);
        JsonMapper jsonMapper = mock(JsonMapper.class);
        RedisIdempotencyStore idempotencyStore = mock(RedisIdempotencyStore.class);
        Payment existing = mock(Payment.class);

        when(idempotencyStore.findPayment("req-123")).thenReturn(Optional.empty());
        when(repository.findByIdempotencyKey("req-123")).thenReturn(Optional.of(existing));

        PaymentService service = new PaymentService(repository, outboxRepository, jsonMapper, idempotencyStore);
        Payment result = service.create("req-123", request());

        assertEquals(existing, result);
        verify(idempotencyStore).put("req-123", existing);
        verify(repository, never()).save(any());
        verifyNoInteractions(outboxRepository, jsonMapper);
    }

    @Test
    void newPaymentPopulatesRedisOnlyAfterCreationPathCompletes() throws Exception {
        PaymentRepository repository = mock(PaymentRepository.class);
        OutboxEventRepository outboxRepository = mock(OutboxEventRepository.class);
        JsonMapper jsonMapper = mock(JsonMapper.class);
        RedisIdempotencyStore idempotencyStore = mock(RedisIdempotencyStore.class);

        when(idempotencyStore.findPayment("req-new")).thenReturn(Optional.empty());
        when(repository.findByIdempotencyKey("req-new")).thenReturn(Optional.empty());
        when(repository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jsonMapper.writeValueAsString(any())).thenReturn("{}");

        PaymentService service = new PaymentService(repository, outboxRepository, jsonMapper, idempotencyStore);
        Payment result = service.create("req-new", request());

        verify(repository).save(result);
        verify(outboxRepository).save(any());
        verify(idempotencyStore).put("req-new", result);
    }

    private CreatePaymentRequest request() {
        return new CreatePaymentRequest(new BigDecimal("42.50"), "USD", "customer-1");
    }
}
