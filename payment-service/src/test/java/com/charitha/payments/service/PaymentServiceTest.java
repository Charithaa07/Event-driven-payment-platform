package com.charitha.payments.service;

import com.charitha.payments.api.CreatePaymentRequest;
import com.charitha.payments.domain.Payment;
import com.charitha.payments.domain.PaymentRepository;
import com.charitha.payments.domain.PaymentStatus;
import com.charitha.payments.idempotency.RedisIdempotencyStore;
import com.charitha.payments.outbox.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentServiceTest {
    @Test
    void cachedIdempotencyResponseBypassesPostgres() {
        PaymentRepository repository = mock(PaymentRepository.class);
        OutboxEventRepository outboxRepository = mock(OutboxEventRepository.class);
        JsonMapper jsonMapper = mock(JsonMapper.class);
        RedisIdempotencyStore idempotencyStore = mock(RedisIdempotencyStore.class);
        Payment existing = payment("req-123", "42.50");

        when(idempotencyStore.findPayment("req-123")).thenReturn(Optional.of(existing));

        PaymentService service = new PaymentService(repository, outboxRepository, jsonMapper, idempotencyStore);
        Payment result = service.create("req-123", request("42.50"));

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
        Payment existing = payment("req-123", "42.50");

        when(idempotencyStore.findPayment("req-123")).thenReturn(Optional.empty());
        when(repository.findByIdempotencyKey("req-123")).thenReturn(Optional.of(existing));

        PaymentService service = new PaymentService(repository, outboxRepository, jsonMapper, idempotencyStore);
        Payment result = service.create("req-123", request("42.50"));

        assertEquals(existing, result);
        verify(idempotencyStore).put("req-123", existing);
        verify(repository, never()).insertIfAbsent(any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(outboxRepository, jsonMapper);
    }

    @Test
    void newPaymentCreatesOutboxAndCachesAfterCreationPathCompletes() throws Exception {
        PaymentRepository repository = mock(PaymentRepository.class);
        OutboxEventRepository outboxRepository = mock(OutboxEventRepository.class);
        JsonMapper jsonMapper = mock(JsonMapper.class);
        RedisIdempotencyStore idempotencyStore = mock(RedisIdempotencyStore.class);

        when(idempotencyStore.findPayment("req-new")).thenReturn(Optional.empty());
        when(repository.findByIdempotencyKey("req-new")).thenReturn(Optional.empty());
        when(repository.insertIfAbsent(any(), eq("req-new"), any(), eq("USD"), eq("customer-1"), eq("ACCEPTED"), any()))
                .thenReturn(1);
        when(jsonMapper.writeValueAsString(any())).thenReturn("{}");

        PaymentService service = new PaymentService(repository, outboxRepository, jsonMapper, idempotencyStore);
        Payment result = service.create("req-new", request("42.50"));

        verify(repository).insertIfAbsent(
                eq(result.getId()),
                eq("req-new"),
                eq(new BigDecimal("42.50")),
                eq("USD"),
                eq("customer-1"),
                eq("ACCEPTED"),
                any()
        );
        verify(outboxRepository).save(any());
        verify(idempotencyStore).put("req-new", result);
    }

    @Test
    void concurrentInsertLoserReturnsWinningPaymentWithoutSecondOutboxEvent() {
        PaymentRepository repository = mock(PaymentRepository.class);
        OutboxEventRepository outboxRepository = mock(OutboxEventRepository.class);
        JsonMapper jsonMapper = mock(JsonMapper.class);
        RedisIdempotencyStore idempotencyStore = mock(RedisIdempotencyStore.class);
        Payment winner = payment("req-race", "42.50");

        when(idempotencyStore.findPayment("req-race")).thenReturn(Optional.empty());
        when(repository.findByIdempotencyKey("req-race"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(repository.insertIfAbsent(any(), eq("req-race"), any(), any(), any(), any(), any()))
                .thenReturn(0);

        PaymentService service = new PaymentService(repository, outboxRepository, jsonMapper, idempotencyStore);
        Payment result = service.create("req-race", request("42.50"));

        assertEquals(winner, result);
        verifyNoInteractions(outboxRepository, jsonMapper);
        verify(idempotencyStore).put("req-race", winner);
    }

    @Test
    void sameIdempotencyKeyWithDifferentRequestIsRejected() {
        PaymentRepository repository = mock(PaymentRepository.class);
        OutboxEventRepository outboxRepository = mock(OutboxEventRepository.class);
        JsonMapper jsonMapper = mock(JsonMapper.class);
        RedisIdempotencyStore idempotencyStore = mock(RedisIdempotencyStore.class);
        Payment existing = payment("req-conflict", "42.50");

        when(idempotencyStore.findPayment("req-conflict")).thenReturn(Optional.of(existing));

        PaymentService service = new PaymentService(repository, outboxRepository, jsonMapper, idempotencyStore);

        assertThrows(
                IdempotencyConflictException.class,
                () -> service.create("req-conflict", request("99.00"))
        );
        verifyNoInteractions(repository, outboxRepository, jsonMapper);
    }

    private CreatePaymentRequest request(String amount) {
        return new CreatePaymentRequest(new BigDecimal(amount), "USD", "customer-1");
    }

    private Payment payment(String idempotencyKey, String amount) {
        return new Payment(
                UUID.randomUUID(),
                idempotencyKey,
                new BigDecimal(amount),
                "USD",
                "customer-1",
                PaymentStatus.ACCEPTED,
                Instant.now()
        );
    }
}
