package io.github.amishpr.ledger.accounting.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    @Bean
    OpenAPI ledgerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Ledger service")
                .version("v1")
                .description("""
                        The system of record for accounts and money movement. Every transaction is double \
                        entry and must balance, amounts are whole cents sent as strings, and nothing posted \
                        is ever edited or deleted: mistakes are corrected with a reversal. Errors are RFC 9457 \
                        Problem Details with a stable `code`."""));
    }
}
