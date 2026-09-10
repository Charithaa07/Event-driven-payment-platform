package com.veda.payments.service;

import com.veda.payments.api.CreatePaymentRequest;
import com.veda.payments.domain.Payment;
import com.veda.payments.domain.PaymentRepository;
import com.veda.payments.outbox.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymentServiceTest {
    @Test
    void returnsExistingPaymentForRepeatedIdempotencyKey() {
        PaymentRepository repository = mock(PaymentRepository.class);
        OutboxEventRepository outboxRepository = mock(OutboxEventRepository.class);
        JsonMapper jsonMapper = mock(JsonMapper.class);
        Payment existing = mock(Payment.class);

        when(repository.findByIdempotencyKey("req-123")).thenReturn(Optional.of(existing));

        PaymentService service = new PaymentService(repository, outboxRepository, jsonMapper);
        Payment result = service.create(
                "req-123",
                new CreatePaymentRequest(new BigDecimal("42.50"), "USD", "customer-1")
        );

        assertEquals(existing, result);
        verify(repository, never()).save(any());
        verifyNoInteractions(outboxRepository, jsonMapper);
    }
}
