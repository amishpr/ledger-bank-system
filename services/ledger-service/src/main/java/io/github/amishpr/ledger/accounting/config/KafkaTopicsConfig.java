package io.github.amishpr.ledger.accounting.config;

import io.github.amishpr.ledger.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topics this service owns. Kafka's admin client creates any that
 * are missing at startup, so a fresh broker needs no manual setup. In a real
 * cluster these would be managed by the platform team, and these beans become
 * a no-op.
 */
@Configuration(proxyBeanMethods = false)
class KafkaTopicsConfig {

    @Bean
    NewTopic accountsTopic(
            @Value("${ledger.kafka.partitions:3}") int partitions, @Value("${ledger.kafka.replicas:1}") short replicas) {
        return TopicBuilder.name(Topics.ACCOUNTS).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    NewTopic transactionsTopic(
            @Value("${ledger.kafka.partitions:3}") int partitions, @Value("${ledger.kafka.replicas:1}") short replicas) {
        return TopicBuilder.name(Topics.TRANSACTIONS).partitions(partitions).replicas(replicas).build();
    }
}
