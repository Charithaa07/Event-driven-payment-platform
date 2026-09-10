package com.charitha.notifications.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {

    @Bean
    NewTopic paymentCreatedTopic() {
        return TopicBuilder.name("payments.created.v1")
                .partitions(6)
                .build();
    }

    @Bean
    NewTopic notificationDeadLetterTopic() {
        return TopicBuilder.name("payments.created.v1.notification.DLT")
                .partitions(6)
                .build();
    }
}
