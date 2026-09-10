package com.charitha.notifications.integration;

import com.charitha.notifications.delivery.DeliveryReceipt;
import com.charitha.notifications.delivery.NotificationProvider;
import com.charitha.notifications.delivery.NotificationProviderException;
import com.charitha.notifications.domain.NotificationDelivery;
import com.charitha.notifications.domain.NotificationDeliveryRepository;
import com.charitha.notifications.domain.NotificationStatus;
import com.charitha.notifications.messaging.PaymentCreatedEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@Import(NotificationServiceIntegrationTest.ProviderTestConfig.class)
@SpringBootTest(properties = {
        "notifications.dispatch.fixed-delay-ms=50",
        "notifications.dispatch.base-backoff-ms=100",
        "notifications.dispatch.max-backoff-ms=200",
        "notifications.dispatch.lease-timeout-ms=1000"
})
class NotificationServiceIntegrationTest {
    private static final String PAYMENT_TOPIC = "payments.created.v1";
    private static final String NOTIFICATION_DLT = PAYMENT_TOPIC + ".notification.DLT";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("notifications")
            .withUsername("notifications")
            .withPassword("notifications");

    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.0.0");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Autowired
    private NotificationDeliveryRepository repository;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE notification_deliveries");
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void duplicateLogicalEventCreatesOneDeliveryAndRetryEventuallySendsIt() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String payload = jsonMapper.writeValueAsString(paymentEvent(
                eventId, paymentId, "customer-notification-1"));

        kafkaTemplate.send(PAYMENT_TOPIC, paymentId.toString(), payload).get(10, TimeUnit.SECONDS);
        kafkaTemplate.send(PAYMENT_TOPIC, paymentId.toString(), payload).get(10, TimeUnit.SECONDS);

        awaitTrue(() -> repository.count() == 1L, Duration.ofSeconds(15));
        awaitTrue(() -> repository.findByPaymentIdOrderByCreatedAtAsc(paymentId).stream()
                .anyMatch(delivery -> delivery.getStatus() == NotificationStatus.SENT), Duration.ofSeconds(15));

        NotificationDelivery delivery = repository.findByPaymentIdOrderByCreatedAtAsc(paymentId).get(0);
        assertEquals(eventId, delivery.getSourceEventId());
        assertEquals(2, delivery.getAttemptCount());
        assertEquals("test-provider", delivery.getProvider());
        assertNotNull(delivery.getProviderMessageId());
        assertTrue(delivery.getProviderMessageId().startsWith("test-"));
    }

    @Test
    void permanentProviderFailureStopsAfterOneAttempt() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String payload = jsonMapper.writeValueAsString(paymentEvent(
                eventId, paymentId, "customer-permanent-failure"));

        kafkaTemplate.send(PAYMENT_TOPIC, paymentId.toString(), payload).get(10, TimeUnit.SECONDS);

        awaitTrue(() -> repository.findByPaymentIdOrderByCreatedAtAsc(paymentId).stream()
                .anyMatch(delivery -> delivery.getStatus() == NotificationStatus.FAILED), Duration.ofSeconds(15));

        NotificationDelivery delivery = repository.findByPaymentIdOrderByCreatedAtAsc(paymentId).get(0);
        assertEquals(1, delivery.getAttemptCount());
        assertTrue(delivery.getLastError().contains("permanent provider rejection"));
    }

    @Test
    void malformedEventMovesToNotificationDeadLetterTopic() throws Exception {
        String key = UUID.randomUUID().toString();
        String malformed = "{not-valid-json";

        kafkaTemplate.send(PAYMENT_TOPIC, key, malformed).get(10, TimeUnit.SECONDS);

        try (KafkaConsumer<String, String> consumer = deadLetterConsumer()) {
            consumer.subscribe(List.of(NOTIFICATION_DLT));
            ConsumerRecord<String, String> record = awaitRecord(consumer, Duration.ofSeconds(15));
            assertEquals(key, record.key());
            assertEquals(malformed, record.value());
        }
        assertEquals(0L, repository.count());
    }

    @Test
    void deliveryQueriesRequireNotificationReadScope() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        publishAndAwait(eventId, paymentId);
        UUID notificationId = repository.findByPaymentIdOrderByCreatedAtAsc(paymentId).get(0).getId();

        mockMvc.perform(get("/api/v1/notifications/{notificationId}", notificationId))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/notifications/{notificationId}", notificationId)
                        .with(jwt()
                                .jwt(token -> token.subject("operator-1"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_ops:read"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/notifications/{notificationId}", notificationId)
                        .with(jwt()
                                .jwt(token -> token.subject("notification-operator-1"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_notification:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationId").value(notificationId.toString()))
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()));

        mockMvc.perform(get("/api/v1/notifications")
                        .param("paymentId", paymentId.toString())
                        .with(jwt()
                                .jwt(token -> token.subject("notification-operator-1"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_notification:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paymentId").value(paymentId.toString()));
    }

    @Test
    void openApiContractIsPublic() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title")
                        .value("Event-Driven Payment Platform Notification API"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/v1/notifications/{notificationId}']").exists());
    }

    private void publishAndAwait(UUID eventId, UUID paymentId) throws Exception {
        String payload = jsonMapper.writeValueAsString(paymentEvent(
                eventId, paymentId, "customer-notification-api"));
        kafkaTemplate.send(PAYMENT_TOPIC, paymentId.toString(), payload).get(10, TimeUnit.SECONDS);
        awaitTrue(() -> !repository.findByPaymentIdOrderByCreatedAtAsc(paymentId).isEmpty(), Duration.ofSeconds(15));
    }

    private PaymentCreatedEvent paymentEvent(UUID eventId, UUID paymentId, String customerId) {
        return new PaymentCreatedEvent(
                eventId,
                paymentId,
                new BigDecimal("42.50"),
                "USD",
                customerId,
                Instant.now()
        );
    }

    private KafkaConsumer<String, String> deadLetterConsumer() {
        Map<String, Object> properties = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "notification-service-dlt-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        );
        return new KafkaConsumer<>(properties);
    }

    private ConsumerRecord<String, String> awaitRecord(
            KafkaConsumer<String, String> consumer,
            Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                return record;
            }
        }
        throw new AssertionError("Timed out waiting for record on " + NOTIFICATION_DLT);
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

    @TestConfiguration
    static class ProviderTestConfig {
        @Bean
        @Primary
        NotificationProvider flakyProvider() {
            ConcurrentHashMap<UUID, AtomicInteger> attempts = new ConcurrentHashMap<>();
            return message -> {
                if (message.customerReference().contains("permanent-failure")) {
                    throw new NotificationProviderException("permanent provider rejection", false);
                }

                int attempt = attempts
                        .computeIfAbsent(message.idempotencyKey(), ignored -> new AtomicInteger())
                        .incrementAndGet();
                if (attempt == 1) {
                    throw new NotificationProviderException("temporary provider outage", true);
                }
                return new DeliveryReceipt(
                        "test-provider",
                        "test-" + message.idempotencyKey()
                );
            };
        }
    }
}
