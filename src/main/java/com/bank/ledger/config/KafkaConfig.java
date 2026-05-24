package com.bank.ledger.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    public static final String TRANSACTION_EVENTS_TOPIC = "transaction-events";
    public static final String TRANSACTION_EVENTS_DLT_TOPIC = "transaction-events-dlt";

    @Bean
    public NewTopic transactionEventsTopic() {
        return TopicBuilder.name(TRANSACTION_EVENTS_TOPIC)
                .partitions(3)
                .replicas(1) // Single broker setup for local development
                .build();
    }

    @Bean
    public NewTopic transactionEventsDltTopic() {
        return TopicBuilder.name(TRANSACTION_EVENTS_DLT_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
