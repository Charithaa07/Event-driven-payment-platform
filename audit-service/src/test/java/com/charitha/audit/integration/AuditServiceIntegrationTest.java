package com.charitha.audit.integration;

import com.charitha.audit.domain.AuditEvent;
import com.charitha.audit.domain.AuditEventRepository;
import com.charitha.audit.messaging.PaymentCreatedEvent;
import com.charitha.audit.service.AuditIntegrityService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
class AuditServiceIntegrationTest {
    private static final String PAYMENT_TOPIC = "payments.created.v1";
    private static final String AUDIT_DLT = PAYMENT_TOPIC + ".audit.DLT";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("audit")
            .withUsername("audit")
            .withPassword("audit");

    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.0.0");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Autowired
    private AuditEventRepository repository;

    @Autowired
    private AuditIntegrityService integrityService;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE audit_events");
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void duplicateLogicalDeliveryCreatesOneImmutableAuditRecord() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String payload = jsonMapper.writeValueAsString(paymentEvent(eventId, paymentId));

        kafkaTemplate.send(PAYMENT_TOPIC, paymentId.toString(), payload).get(10, TimeUnit.SECONDS);
        kafkaTemplate.send(PAYMENT_TOPIC, paymentId.toString(), payload).get(10, TimeUnit.SECONDS);

        awaitTrue(() -> repository.existsById(eventId), Duration.ofSeconds(15));
        awaitTrue(() -> repository.count() == 1L, Duration.ofSeconds(5));

        AuditEvent stored = repository.findById(eventId).orElseThrow();
        assertEquals(paymentId, stored.getAggregateId());
        assertEquals("PAYMENT_CREATED_V1", stored.getEventType());
        assertEquals("PAYMENT", stored.getAggregateType());
        assertEquals(64, stored.getRecordSha256().length());
        assertTrue(integrityService.verify(stored));
    }

    @Test
    void databaseRejectsAuditRecordMutation() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        publishAndAwait(eventId, paymentId);

        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE audit_events SET customer_id = ? WHERE event_id = ?",
                "tampered-customer",
                eventId
        ));

        assertEquals("customer-audit-1", repository.findById(eventId).orElseThrow().getCustomerId());
    }

    @Test
    void malformedEventMovesToAuditDeadLetterTopic() throws Exception {
        String key = UUID.randomUUID().toString();
        String malformed = "{not-valid-json";

        kafkaTemplate.send(PAYMENT_TOPIC, key, malformed).get(10, TimeUnit.SECONDS);

        try (KafkaConsumer<String, String> consumer = deadLetterConsumer()) {
            consumer.subscribe(List.of(AUDIT_DLT));
            ConsumerRecord<String, String> record = awaitRecord(consumer, Duration.ofSeconds(15));
            assertEquals(key, record.key());
            assertEquals(malformed, record.value());
        }
        assertEquals(0L, repository.count());
    }

    @Test
    void auditQueriesRequireAuditReadAndHidePayloadFromTimeline() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        publishAndAwait(eventId, paymentId);

        mockMvc.perform(get("/api/v1/audit/payments/{paymentId}", paymentId))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/audit/payments/{paymentId}", paymentId)
                        .with(jwt()
                                .jwt(token -> token.subject("operator-1"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_ops:read"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/audit/payments/{paymentId}", paymentId)
                        .with(jwt()
                                .jwt(token -> token.subject("auditor-1"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_audit:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value(eventId.toString()))
                .andExpect(jsonPath("$[0].payload").doesNotExist());

        mockMvc.perform(get("/api/v1/audit/events/{eventId}", eventId)
                        .with(jwt()
                                .jwt(token -> token.subject("auditor-1"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_audit:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").exists())
                .andExpect(jsonPath("$.integrityValid").value(true));
    }

    @Test
    void openApiContractIsPublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Event-Driven Payment Platform Audit API"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/v1/audit/payments/{paymentId}']").exists());
    }

    private void publishAndAwait(UUID eventId, UUID paymentId) throws Exception {
        String payload = jsonMapper.writeValueAsString(paymentEvent(eventId, paymentId));
        kafkaTemplate.send(PAYMENT_TOPIC, paymentId.toString(), payload).get(10, TimeUnit.SECONDS);
        awaitTrue(() -> repository.existsById(eventId), Duration.ofSeconds(15));
    }

    private PaymentCreatedEvent paymentEvent(UUID eventId, UUID paymentId) {
        return new PaymentCreatedEvent(
                eventId,
                paymentId,
                new BigDecimal("42.50"),
                "USD",
                "customer-audit-1",
                Instant.now()
        );
    }

    private KafkaConsumer<String, String> deadLetterConsumer() {
        Map<String, Object> properties = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "audit-service-dlt-test-" + UUID.randomUUID(),
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
        throw new AssertionError("Timed out waiting for record on " + AUDIT_DLT);
    }

    private void awaitTrue(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("Condition not satisfied within " + timeout);
    }
}
