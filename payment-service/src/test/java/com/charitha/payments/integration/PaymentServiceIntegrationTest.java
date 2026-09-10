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
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = "outbox.relay.enabled=false")
class PaymentServiceIntegrationTest {
    private static final String CUSTOMER_ID = "customer-1";

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

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        paymentRepository.deleteAll();
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void concurrentSameKeyRequestsResolveToOnePaymentAndOneOutboxEvent() throws Exception {
        String key = "race-" + UUID.randomUUID();
        CreatePaymentRequest request = request("42.50");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Payment> first = executor.submit(() -> createAfterBarrier(key, request, CUSTOMER_ID, ready, start));
            Future<Payment> second = executor.submit(() -> createAfterBarrier(key, request, CUSTOMER_ID, ready, start));

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
    void sameKeyWithDifferentRequestIsRejectedForSameCustomer() {
        String key = "conflict-" + UUID.randomUUID();

        paymentService.create(key, request("42.50"), CUSTOMER_ID);

        assertThrows(
                IdempotencyConflictException.class,
                () -> paymentService.create(key, request("99.00"), CUSTOMER_ID)
        );
        assertEquals(1L, paymentRepository.count());
        assertEquals(1L, outboxRepository.count());
    }

    @Test
    void sameIdempotencyKeyCanBeUsedByDifferentCustomers() {
        String sharedKey = "shared-" + UUID.randomUUID();

        Payment first = paymentService.create(sharedKey, request("42.50"), "customer-a");
        Payment second = paymentService.create(sharedKey, request("42.50"), "customer-b");

        assertNotEquals(first.getId(), second.getId());
        assertEquals(2L, paymentRepository.count());
        assertEquals(2L, outboxRepository.count());
    }

    @Test
    void rollbackDoesNotPopulateRedisOrLeaveDatabaseRows() {
        String key = "rollback-" + UUID.randomUUID();
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        template.executeWithoutResult(status -> {
            paymentService.create(key, request("42.50"), CUSTOMER_ID);
            assertTrue(idempotencyStore.findPayment(CUSTOMER_ID, key).isEmpty());
            status.setRollbackOnly();
        });

        assertTrue(paymentRepository.findByCustomerIdAndIdempotencyKey(CUSTOMER_ID, key).isEmpty());
        assertEquals(0L, outboxRepository.count());
        assertTrue(idempotencyStore.findPayment(CUSTOMER_ID, key).isEmpty());
    }

    @Test
    void committedPaymentWarmsRedisPersistsOutboxAndIsClaimable() {
        String key = "commit-" + UUID.randomUUID();
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        Payment created = paymentService.create(key, request("42.50"), CUSTOMER_ID);

        assertTrue(paymentRepository.findById(created.getId()).isPresent());
        assertEquals(1L, outboxRepository.count());
        Payment cached = idempotencyStore.findPayment(CUSTOMER_ID, key).orElseThrow();
        assertEquals(created.getId(), cached.getId());

        Integer claimableCount = template.execute(status -> outboxRepository.findClaimableBatch().size());
        assertEquals(1, claimableCount);
    }

    @Test
    void unauthenticatedPaymentCreationReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "http-unauthenticated")
                        .content("{\"amount\":42.50,\"currency\":\"USD\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithoutWriteScopeReturns403() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                        .with(jwt()
                                .jwt(token -> token.subject("customer-http"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_payments:read")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "http-wrong-scope")
                        .content("{\"amount\":42.50,\"currency\":\"USD\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void authenticatedCustomerIdentityComesFromJwtSubject() throws Exception {
        String key = "http-create-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/payments")
                        .with(jwt()
                                .jwt(token -> token.subject("customer-http"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_payments:write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content("{\"amount\":42.50,\"currency\":\"USD\",\"customerId\":\"spoofed-customer\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value("customer-http"));

        Payment persisted = paymentRepository.findByCustomerIdAndIdempotencyKey("customer-http", key).orElseThrow();
        assertEquals("customer-http", persisted.getCustomerId());
    }

    @Test
    void paymentReadRequiresReadScopeAndOwnership() throws Exception {
        Payment created = paymentService.create("http-read-" + UUID.randomUUID(), request("42.50"), CUSTOMER_ID);

        mockMvc.perform(get("/api/v1/payments/{paymentId}", created.getId())
                        .with(jwt()
                                .jwt(token -> token.subject(CUSTOMER_ID))
                                .authorities(new SimpleGrantedAuthority("SCOPE_payments:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(created.getId().toString()));

        mockMvc.perform(get("/api/v1/payments/{paymentId}", created.getId())
                        .with(jwt()
                                .jwt(token -> token.subject("different-customer"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_payments:read"))))
                .andExpect(status().isNotFound());
    }

    private Payment createAfterBarrier(String key,
                                       CreatePaymentRequest request,
                                       String customerId,
                                       CountDownLatch ready,
                                       CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for concurrent request barrier");
        }
        return paymentService.create(key, request, customerId);
    }

    private CreatePaymentRequest request(String amount) {
        return new CreatePaymentRequest(new BigDecimal(amount), "USD");
    }
}
