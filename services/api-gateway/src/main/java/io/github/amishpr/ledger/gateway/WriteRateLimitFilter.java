package io.github.amishpr.ledger.gateway;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.time.Duration;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Limits how fast one client can write (POST, PATCH, DELETE) through the
 * gateway. The client is the authenticated principal when there is one, and
 * otherwise the connection's address. It never trusts an X-Forwarded-For
 * header, which any client can set to anything.
 *
 * <p>Buckets live in this instance's memory, so with N gateway instances the
 * effective limit is roughly N times higher. A shared store such as Redis is
 * the next step when that matters; for a single edge it is the simpler tool.
 */
@Component
class WriteRateLimitFilter implements GlobalFilter, Ordered {

    private final GatewayProperties.RateLimit limits;
    private final JsonMapper json;
    private final Cache<String, TokenBucket> buckets = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    WriteRateLimitFilter(GatewayProperties properties, JsonMapper json) {
        this.limits = properties.rateLimit();
        this.json = json;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        HttpMethod method = exchange.getRequest().getMethod();
        if (!limits.enabled() || method == HttpMethod.GET || method == HttpMethod.HEAD || method == HttpMethod.OPTIONS) {
            return chain.filter(exchange);
        }
        return exchange.getPrincipal()
                .map(Principal::getName)
                .defaultIfEmpty(remoteAddress(exchange))
                .flatMap(client -> {
                    long now = System.nanoTime();
                    TokenBucket bucket = buckets.get(client, key -> new TokenBucket(limits.capacity(), limits.refillPerSecond(), now));
                    if (bucket.tryConsume(now)) {
                        exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", Integer.toString(bucket.remaining(now)));
                        return chain.filter(exchange);
                    }
                    exchange.getResponse().getHeaders().set("Retry-After", "1");
                    exchange.getResponse().getHeaders().set("X-RateLimit-Remaining", "0");
                    return ProblemResponses.write(
                            exchange,
                            json,
                            HttpStatus.TOO_MANY_REQUESTS,
                            "RATE_LIMITED",
                            "Too many changes in a short time. Wait a second and try again.",
                            TraceIds.of(exchange));
                });
    }

    private static String remoteAddress(ServerWebExchange exchange) {
        InetSocketAddress address = exchange.getRequest().getRemoteAddress();
        return address == null || address.getAddress() == null ? "unknown" : address.getAddress().getHostAddress();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
