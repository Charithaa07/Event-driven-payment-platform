package com.charitha.transactions.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<TransactionRecord, UUID> {
    Optional<TransactionRecord> findByPaymentId(UUID paymentId);
}
