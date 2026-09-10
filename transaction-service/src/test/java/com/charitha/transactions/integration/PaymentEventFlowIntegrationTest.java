package com.charitha.transactions.integration;

import com.charitha.transactions.domain.TransactionRecord;
import com.charitha.transactions.domain.TransactionRepository;
import com.charitha.transactions.idempotency.ProcessedEventRepository;
import com.charitha.transactions.messaging.PaymentCreatedEvent;
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
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka-native:4.0.0");

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void cleanDatabase() {
        transactionRepository.deleteAll();
        processedEventRepository.deleteAll();
    }

    @Test
    void duplicateKafkaDeliveryProducesExactlyOneBusinessTransaction() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        PaymentCreatedEvent event = new PaymentCreatedEvent(
                eventId,
                paymentId,
                new BigDecimal("42.50"),
                "USD",
                "customer-integration-1",
                Instant.now()
        );
        String payload = jsonMapper.writeValueAsString(event);

        kafkaTemplate.send(PAYMENT_CREATED_TOPIC, paymentId.toString(), payload).get(10, TimeUnit.SECONDS);
        kafkaTemplate.send(PAYMENT_CREATED_TOPIC, paymentId.toString(), payload).get(10, TimeUnit.SECONDS);

        awaitTrue(() -> processedEventRepository.existsById(eventId), Duration.ofSeconds(15));
        // Give the second delivery time to pass through the idempotency check.
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
    void malformedEventIsPublishedToDeadLetterTopic() throws Exception {
        String malformedPayload = "{not-valid-json";
        String key = UUID.randomUUID().toString();

        kafkaTemplate.send(PAYMENT_CREATED_TOPIC, key, malformedPayload).get(10, TimeUnit.SECONDS);

        try (KafkaConsumer<String, String> consumer = deadLetterConsumer()) {
            consumer.subscribe(List.of(PAYMENT_CREATED_DLT));

            ConsumerRecord<String, String> deadLetter = awaitRecord(consumer, Duration.ofSeconds(15));
            assertEquals(key, deadLetter.key());
            assertEquals(malformedPayload, deadLetter.value());
        }
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
