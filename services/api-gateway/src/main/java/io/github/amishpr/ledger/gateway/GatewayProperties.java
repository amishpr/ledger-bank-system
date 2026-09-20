package io.github.amishpr.ledger.gateway;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Edge settings under {@code gateway}.
 *
 * @param allowedOrigins browser origins allowed to call the API (CORS)
 * @param jwtEnabled require a bearer token on the API. Off by default because
 *     the dashboard has no login; turn on together with
 *     {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}
 * @param rateLimit write request limits per client
 */
@ConfigurationProperties("gateway")
public record GatewayProperties(
        @DefaultValue("http://localhost:5173") List<String> allowedOrigins,
        @DefaultValue("false") boolean jwtEnabled,
        @DefaultValue RateLimit rateLimit) {

    /**
     * A token bucket per client for POST, PATCH and DELETE. Reads are not
     * limited, since the dashboard reloads several lists after every change.
     *
     * @param capacity the most requests a client can burst at once
     * @param refillPerSecond how quickly the bucket refills afterwards
     */
    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("30") int capacity,
            @DefaultValue("10") int refillPerSecond) {}
}
