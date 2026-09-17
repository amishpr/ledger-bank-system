package io.github.amishpr.ledger.insights;

import io.github.amishpr.ledger.events.Topics;
import io.github.amishpr.ledger.platform.kafka.DeadLetterTopics;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
class InsightsConfig {

    @Bean
    NewTopic insightsDeadLetterTopic(
            @Value("${spring.application.name}") String name, @Value("${ledger.kafka.replicas:1}") short replicas) {
        return TopicBuilder.name(DeadLetterTopics.forTopic(Topics.TRANSACTIONS, name)).partitions(1).replicas(replicas).build();
    }

    @Bean
    OpenAPI insightsOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Insights service")
                .version("v1")
                .description("""
                        Spending reports. This service keeps its own read model, built from the ledger's \
                        transaction events, and never queries the ledger directly."""));
    }
}
