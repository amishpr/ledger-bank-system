package io.github.amishpr.ledger.recurring.config;

import io.github.amishpr.ledger.events.Topics;
import io.github.amishpr.ledger.recurring.client.LedgerClient;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.web.service.registry.ImportHttpServices;

/**
 * The ledger client is a generated proxy, registered in the "ledger" group;
 * its base URL and timeouts live under {@code spring.http.serviceclient.ledger}.
 */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "ledger", types = LedgerClient.class)
class IntegrationConfig {

    @Bean
    NewTopic recurringTransfersTopic(
            @Value("${ledger.kafka.partitions:3}") int partitions, @Value("${ledger.kafka.replicas:1}") short replicas) {
        return TopicBuilder.name(Topics.RECURRING_TRANSFERS).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    OpenAPI recurringOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Recurring transfer service")
                .version("v1")
                .description("""
                        Standing instructions to move money between asset accounts on a schedule. Each \
                        occurrence is posted through the ledger service with an idempotency key derived from \
                        the schedule and its due time, so it can never post twice."""));
    }
}
