package com.charitha.payments.integration;

import com.charitha.payments.api.CreatePaymentRequest;
import com.charitha.payments.domain.Payment;
import com.charitha.payments.domain.PaymentRepository;
import com.charitha.payments.idempotency.RedisIdempotencyStore;
import com.charitha.payments.outbox.OutboxEventRepository;
import com.charitha.payments.service.IdempotencyConflictException;
import com.charitha.payments.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest(properties = "outbox.relay.enabled=false")
class PaymentServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("payments")
            .withUsername("payments")
            .withPassword("payments");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private RedisIdempotencyStore idempotencyStore;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        outboxRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    @Test
    void concurrentSameKeyRequestsResolveToOnePaymentAndOneOutboxEvent() throws Exception {
        String key = "race-" + UUID.randomUUID();
        CreatePaymentRequest request = request("42.50");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Payment> first = executor.submit(() -> createAfterBarrier(key, request, ready, start));
            Future<Payment> second = executor.submit(() -> createAfterBarrier(key, request, ready, start));

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            Payment firstResult = first.get(15, TimeUnit.SECONDS);
            Payment secondResult = second.get(15, TimeUnit.SECONDS);

            assertEquals(firstResult.getId(), secondResult.getId());
            assertEquals(1L, paymentRepository.count());
            assertEquals(1L, outboxRepository.count());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void sameKeyWithDifferentRequestIsRejected() {
        String key = "conflict-" + UUID.randomUUID();

        paymentService.create(key, request("42.50"));

        assertThrows(
                IdempotencyConflictException.class,
                () -> paymentService.create(key, request("99.00"))
        );
        assertEquals(1L, paymentRepository.count());
        assertEquals(1L, outboxRepository.count());
    }

    @Test
    void rollbackDoesNotPopulateRedisOrLeaveDatabaseRows() {
        String key = "rollback-" + UUID.randomUUID();
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        template.executeWithoutResult(status -> {
            paymentService.create(key, request("42.50"));
            assertTrue(idempotencyStore.findPayment(key).isEmpty());
            status.setRollbackOnly();
        });

        assertTrue(paymentRepository.findByIdempotencyKey(key).isEmpty());
        assertEquals(0L, outboxRepository.count());
        assertTrue(idempotencyStore.findPayment(key).isEmpty());
    }

    @Test
    void committedPaymentWarmsRedisPersistsOutboxAndIsClaimable() {
        String key = "commit-" + UUID.randomUUID();
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        Payment created = paymentService.create(key, request("42.50"));

        assertTrue(paymentRepository.findById(created.getId()).isPresent());
        assertEquals(1L, outboxRepository.count());
        Payment cached = idempotencyStore.findPayment(key).orElseThrow();
        assertEquals(created.getId(), cached.getId());

        Integer claimableCount = template.execute(status -> outboxRepository.findClaimableBatch().size());
        assertEquals(1, claimableCount);
    }

    private Payment createAfterBarrier(String key,
                                       CreatePaymentRequest request,
                                       CountDownLatch ready,
                                       CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for concurrent request barrier");
        }
        return paymentService.create(key, request);
    }

    private CreatePaymentRequest request(String amount) {
        return new CreatePaymentRequest(new BigDecimal(amount), "USD", "customer-1");
    }
}
