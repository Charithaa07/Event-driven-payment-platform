package com.charitha.transactions.service;

import com.charitha.transactions.domain.TransactionRecord;
import com.charitha.transactions.domain.TransactionRepository;
import com.charitha.transactions.idempotency.ProcessedEvent;
import com.charitha.transactions.idempotency.ProcessedEventRepository;
import com.charitha.transactions.messaging.PaymentCreatedEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TransactionProcessorTest {

    @Test
    void ignoresEventThatWasAlreadyProcessed() {
        TransactionRepository transactions = mock(TransactionRepository.class);
        ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
        PaymentCreatedEvent event = event();

        when(processedEvents.existsById(event.eventId())).thenReturn(true);

        TransactionProcessor processor = new TransactionProcessor(transactions, processedEvents);
        processor.process(event);

        verifyNoInteractions(transactions);
        verify(processedEvents, never()).save(any(ProcessedEvent.class));
    }

    @Test
    void createsTransactionAndMarksEventProcessedAtomically() {
        TransactionRepository transactions = mock(TransactionRepository.class);
        ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
        PaymentCreatedEvent event = event();

        when(processedEvents.existsById(event.eventId())).thenReturn(false);
        when(transactions.findByPaymentId(event.paymentId())).thenReturn(Optional.empty());
        when(transactions.save(any(TransactionRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransactionProcessor processor = new TransactionProcessor(transactions, processedEvents);
        processor.process(event);

        verify(transactions).save(any(TransactionRecord.class));
        verify(processedEvents).save(any(ProcessedEvent.class));
    }

    private PaymentCreatedEvent event() {
        return new PaymentCreatedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("42.50"),
                "USD",
                "customer-1",
                Instant.parse("2026-09-10T17:00:00Z")
        );
    }
}
