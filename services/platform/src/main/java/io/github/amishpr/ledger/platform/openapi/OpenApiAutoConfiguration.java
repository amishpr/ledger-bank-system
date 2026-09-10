package io.github.amishpr.ledger.platform.openapi;

import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Points every service's OpenAPI document at the public gateway address. */
@AutoConfiguration
@ConditionalOnClass(OpenApiCustomizer.class)
@EnableConfigurationProperties(OpenApiProperties.class)
public class OpenApiAutoConfiguration {

    @Bean
    OpenApiCustomizer gatewayServerCustomizer(OpenApiProperties properties) {
        return openApi -> openApi.setServers(
                List.of(new Server().url(properties.serverUrl()).description(properties.serverDescription())));
    }
}
