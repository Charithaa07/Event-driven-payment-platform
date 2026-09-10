package com.charitha.payments.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByCustomerIdAndIdempotencyKey(String customerId, String idempotencyKey);

    Optional<Payment> findByIdAndCustomerId(UUID id, String customerId);

    @Modifying
    @Query(value = """
            INSERT INTO payments (
                id, idempotency_key, amount, currency, customer_id, status, created_at
            ) VALUES (
                :id, :idempotencyKey, :amount, :currency, :customerId, :status, :createdAt
            )
            ON CONFLICT (customer_id, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("idempotencyKey") String idempotencyKey,
                       @Param("amount") BigDecimal amount,
                       @Param("currency") String currency,
                       @Param("customerId") String customerId,
                       @Param("status") String status,
                       @Param("createdAt") Instant createdAt);
}
