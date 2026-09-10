package com.charitha.transactions.service;

import com.charitha.transactions.domain.TransactionRecord;
import com.charitha.transactions.domain.TransactionRepository;
import com.charitha.transactions.domain.TransactionStatus;
import com.charitha.transactions.idempotency.ProcessedEvent;
import com.charitha.transactions.idempotency.ProcessedEventRepository;
import com.charitha.transactions.messaging.PaymentCreatedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class TransactionProcessor {
    private final TransactionRepository transactionRepository;
    private final ProcessedEventRepository processedEventRepository;

    public TransactionProcessor(TransactionRepository transactionRepository,
                                ProcessedEventRepository processedEventRepository) {
        this.transactionRepository = transactionRepository;
        this.processedEventRepository = processedEventRepository;
    }

    @Transactional
    public void process(PaymentCreatedEvent event) {
        if (processedEventRepository.existsById(event.eventId())) {
            return;
        }

        transactionRepository.findByPaymentId(event.paymentId())
                .orElseGet(() -> transactionRepository.save(new TransactionRecord(
                        UUID.randomUUID(),
                        event.paymentId(),
                        event.eventId(),
                        event.amount(),
                        event.currency(),
                        event.customerId(),
                        TransactionStatus.RECEIVED,
                        event.occurredAt(),
                        Instant.now()
                )));

        processedEventRepository.save(new ProcessedEvent(event.eventId(), Instant.now()));
    }
}
