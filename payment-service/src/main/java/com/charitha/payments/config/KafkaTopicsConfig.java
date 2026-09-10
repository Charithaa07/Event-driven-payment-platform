package com.charitha.payments.config;

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
}
