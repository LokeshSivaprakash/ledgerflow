package dev.ledgerflow.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(prefix = "ledger.events", name = "publisher", havingValue = "kafka")
public class KafkaTopicConfig {

    @Bean
    NewTopic ledgerTransactionsTopic(@Value("${ledger.events.topic:ledger.transactions}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }
}
