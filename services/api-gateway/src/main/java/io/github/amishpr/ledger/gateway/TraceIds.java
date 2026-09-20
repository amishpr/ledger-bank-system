package io.github.amishpr.ledger.gateway;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.web.server.ServerWebExchange;

/** The trace id of the request an exchange belongs to, read from its server observation. */
final class TraceIds {

    private TraceIds() {}

    static String of(ServerWebExchange exchange) {
        return ServerRequestObservationContext.findCurrent(exchange.getAttributes())
                .map(context -> {
                    TracingContext tracing = context.get(TracingContext.class);
                    Span span = tracing == null ? null : tracing.getSpan();
                    return span == null ? null : span.context().traceId();
                })
                .orElse(null);
    }
}
