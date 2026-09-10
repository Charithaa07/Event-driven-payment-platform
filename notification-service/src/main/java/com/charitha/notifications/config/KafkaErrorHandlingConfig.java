package com.charitha.notifications.config;

import com.charitha.notifications.observability.NotificationMetrics;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaErrorHandlingConfig {

    @Bean
    DeadLetterPublishingRecoverer notificationDeadLetterPublishingRecoverer(
            KafkaTemplate<Object, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(
                        record.topic() + ".notification.DLT",
                        record.partition()
                )
        );
        recoverer.setFailIfSendResultIsError(true);
        return recoverer;
    }

    @Bean
    DefaultErrorHandler notificationKafkaErrorHandler(
            DeadLetterPublishingRecoverer recoverer,
            NotificationMetrics metrics) {
        ConsumerRecordRecoverer observedRecoverer = (record, exception) -> {
            recoverer.accept(record, exception);
            metrics.recordIngestion("dead_lettered");
        };

        DefaultErrorHandler handler = new DefaultErrorHandler(
                observedRecoverer,
                new FixedBackOff(1_000L, 2L)
        );
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }
}
