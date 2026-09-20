package io.github.amishpr.ledger.gateway;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Puts the request's trace id on every response as {@code X-Trace-Id}. When
 * someone reports a problem, that one header finds the whole request, across
 * every service it touched, in Jaeger.
 */
@Component
class TraceIdResponseFilter implements WebFilter, Ordered {

    static final String HEADER = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        exchange.getResponse().beforeCommit(() -> {
            String traceId = TraceIds.of(exchange);
            if (traceId != null) {
                exchange.getResponse().getHeaders().set(HEADER, traceId);
            }
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
