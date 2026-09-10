package com.charitha.payments.integration;

import com.charitha.payments.api.CreatePaymentRequest;
import com.charitha.payments.domain.Payment;
import com.charitha.payments.domain.PaymentRepository;
import com.charitha.payments.outbox.OutboxEventRepository;
import com.charitha.payments.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest(properties = "outbox.relay.enabled=false")
class PaymentIdempotencyStressTest {
    private static final int CONCURRENCY = 8;
    private static final String CUSTOMER_ID = "stress-customer";

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

    @BeforeEach
    void clearState() {
        outboxRepository.deleteAll();
        paymentRepository.deleteAll();
    }

    @Test
    void simultaneousFirstWritersResolveToOnePaymentAndOneOutboxRow() throws Exception {
        String key = "stress-" + UUID.randomUUID();
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("42.50"), "USD");
        CountDownLatch ready = new CountDownLatch(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);

        try {
            @SuppressWarnings("unchecked")
            Future<Payment>[] futures = new Future[CONCURRENCY];
            for (int i = 0; i < CONCURRENCY; i++) {
                futures[i] = executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for stress-test barrier");
                    }
                    return paymentService.create(key, request, CUSTOMER_ID);
                });
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            Set<UUID> paymentIds = new HashSet<>();
            for (Future<Payment> future : futures) {
                paymentIds.add(future.get(20, TimeUnit.SECONDS).getId());
            }

            assertEquals(1, paymentIds.size(), "all concurrent callers must resolve to the same payment");
            assertEquals(1L, paymentRepository.count());
            assertEquals(1L, outboxRepository.count());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
