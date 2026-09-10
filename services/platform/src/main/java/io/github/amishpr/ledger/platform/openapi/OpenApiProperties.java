package io.github.amishpr.ledger.platform.openapi;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Under {@code ledger.openapi}. {@code serverUrl} is the public address clients
 * call, normally the API gateway, so "Try it out" in Swagger UI goes through the
 * same front door the dashboard uses rather than straight at a service.
 */
@ConfigurationProperties("ledger.openapi")
public record OpenApiProperties(
        @DefaultValue("http://localhost:4000") String serverUrl,
        @DefaultValue("API gateway") String serverDescription) {}
