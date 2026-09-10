package com.charitha.transactions.integration;

import com.charitha.transactions.domain.TransactionRecord;
import com.charitha.transactions.domain.TransactionRepository;
import com.charitha.transactions.idempotency.ProcessedEventRepository;
import com.charitha.transactions.messaging.PaymentCreatedEvent;
import com.charitha.transactions.recovery.DeadLetterEvent;
import com.charitha.transactions.recovery.DeadLetterEventRepository;
import com.charitha.transactions.recovery.DeadLetterStatus;
import com.charitha.transactions.recovery.DltReplayService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
class PaymentEventFlowIntegrationTest {

    private static final String PAYMENT_CREATED_TOPIC = "payments.created.v1";
    private static final String PAYMENT_CREATED_DLT = PAYMENT_CREATED_TOPIC + ".DLT";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("transactions")
            .withUsername("transactions")
            .withPassword("transactions");

    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.0.0");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private DeadLetterEventRepository deadLetterRepository;

    @Autowired
    private DltReplayService replayService;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void cleanDatabase() {
        deadLetterRepository.deleteAll();
        transactionRepository.deleteAll();
        processedEventRepository.deleteAll();
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void duplicateKafkaDeliveryProducesExactlyOneBusinessTransaction() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        PaymentCreatedEvent event = paymentEvent(eventId, paymentId);
        String payload = jsonMapper.writeValueAsString(event);

        kafkaTemplate.send(PAYMENT_CREATED_TOPIC, paymentId.toString(), payload).get(10, TimeUnit.SECONDS);
        kafkaTemplate.send(PAYMENT_CREATED_TOPIC, paymentId.toString(), payload).get(10, TimeUnit.SECONDS);

        awaitTrue(() -> processedEventRepository.existsById(eventId), Duration.ofSeconds(15));
        Thread.sleep(1_000L);

        Optional<TransactionRecord> persisted = transactionRepository.findByPaymentId(paymentId);
        assertTrue(persisted.isPresent());
        assertEquals(1L, transactionRepository.count());
        assertEquals(1L, processedEventRepository.count());
        assertEquals(eventId, persisted.orElseThrow().getSourceEventId());
        assertEquals(new BigDecimal("42.50"), persisted.orElseThrow().getAmount());
        assertEquals("USD", persisted.orElseThrow().getCurrency());
    }

    @Test
    void malformedEventIsPublishedToDltAndDurablyIndexed() throws Exception {
        String malformedPayload = "{not-valid-json";
        String key = UUID.randomUUID().toString();

        kafkaTemplate.send(PAYMENT_CREATED_TOPIC, key, malformedPayload).get(10, TimeUnit.SECONDS);

        try (KafkaConsumer<String, String> consumer = deadLetterConsumer()) {
            consumer.subscribe(List.of(PAYMENT_CREATED_DLT));
            ConsumerRecord<String, String> deadLetter = awaitRecord(consumer, Duration.ofSeconds(15));
            assertEquals(key, deadLetter.key());
            assertEquals(malformedPayload, deadLetter.value());
        }

        awaitTrue(() -> findDeadLetterByKey(key).isPresent(), Duration.ofSeconds(15));
        DeadLetterEvent indexed = findDeadLetterByKey(key).orElseThrow();
        assertEquals(DeadLetterStatus.PENDING, indexed.getStatus());
        assertEquals(PAYMENT_CREATED_TOPIC, indexed.getSourceTopic());
        assertEquals(PAYMENT_CREATED_DLT, indexed.getDltTopic());
    }

    @Test
    void replayedDltEventReentersOriginalTopicAndCreatesTransaction() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String key = paymentId.toString();
        String payload = jsonMapper.writeValueAsString(paymentEvent(eventId, paymentId));

        kafkaTemplate.send(PAYMENT_CREATED_DLT, key, payload).get(10, TimeUnit.SECONDS);
        awaitTrue(() -> findDeadLetterByKey(key).isPresent(), Duration.ofSeconds(15));

        DeadLetterEvent indexed = findDeadLetterByKey(key).orElseThrow();
        DeadLetterEvent replayed = replayService.replay(indexed.getId(), "operator-integration");

        assertEquals(DeadLetterStatus.REPLAYED, replayed.getStatus());
        assertEquals(1, replayed.getReplayAttempts());
        assertEquals("operator-integration", replayed.getReplayedBy());
        awaitTrue(() -> transactionRepository.findByPaymentId(paymentId).isPresent(), Duration.ofSeconds(15));

        DeadLetterEvent secondReplay = replayService.replay(indexed.getId(), "operator-integration");
        assertEquals(1, secondReplay.getReplayAttempts());
        assertEquals(1L, transactionRepository.count());
    }

    @Test
    void dltOperationsRequireReadAndWriteScopes() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        DeadLetterEvent recoveryRecord = deadLetterRepository.save(new DeadLetterEvent(
                eventId,
                PAYMENT_CREATED_TOPIC,
                PAYMENT_CREATED_DLT,
                0,
                UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE,
                paymentId.toString(),
                jsonMapper.writeValueAsString(paymentEvent(UUID.randomUUID(), paymentId)),
                Instant.now()
        ));

        mockMvc.perform(get("/api/v1/operations/dlt"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/operations/dlt")
                        .with(jwt()
                                .jwt(token -> token.subject("operator-1"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_payments:read"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/operations/dlt")
                        .with(jwt()
                                .jwt(token -> token.subject("operator-1"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_ops:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(recoveryRecord.getId().toString()))
                .andExpect(jsonPath("$[0].payload").doesNotExist());

        mockMvc.perform(get("/api/v1/operations/dlt/{eventId}", eventId)
                        .with(jwt()
                                .jwt(token -> token.subject("operator-1"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_ops:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").exists());

        mockMvc.perform(post("/api/v1/operations/dlt/{eventId}/replay", eventId)
                        .with(jwt()
                                .jwt(token -> token.subject("operator-1"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_ops:read"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/operations/dlt/{eventId}/replay", eventId)
                        .with(jwt()
                                .jwt(token -> token.subject("operator-1"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_ops:write"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPLAYED"))
                .andExpect(jsonPath("$.replayedBy").value("operator-1"));
    }

    private PaymentCreatedEvent paymentEvent(UUID eventId, UUID paymentId) {
        return new PaymentCreatedEvent(
                eventId,
                paymentId,
                new BigDecimal("42.50"),
                "USD",
                "customer-integration-1",
                Instant.now()
        );
    }

    private Optional<DeadLetterEvent> findDeadLetterByKey(String key) {
        return deadLetterRepository.findAll().stream()
                .filter(event -> key.equals(event.getMessageKey()))
                .findFirst();
    }

    private KafkaConsumer<String, String> deadLetterConsumer() {
        Map<String, Object> properties = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "transaction-service-dlt-integration-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        );
        return new KafkaConsumer<>(properties);
    }

    private ConsumerRecord<String, String> awaitRecord(KafkaConsumer<String, String> consumer,
                                                        Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                return record;
            }
        }
        throw new AssertionError("Timed out waiting for record on " + PAYMENT_CREATED_DLT);
    }

    private void awaitTrue(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("Condition was not satisfied within " + timeout);
    }
}
