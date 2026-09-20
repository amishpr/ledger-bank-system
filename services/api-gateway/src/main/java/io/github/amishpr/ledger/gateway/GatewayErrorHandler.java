package io.github.amishpr.ledger.gateway;

import java.net.ConnectException;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.support.TimeoutException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Errors that start at the gateway itself, as Problem Details: no route
 * matched, a service did not answer in time, or a service could not be
 * reached at all. Ordered ahead of Boot's default handler, which would
 * otherwise answer in its own, different JSON shape.
 */
@Component
@Order(-2)
class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final JsonMapper json;

    GatewayErrorHandler(JsonMapper json) {
        this.json = json;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        String traceId = TraceIds.of(exchange);
        if (error instanceof TimeoutException) {
            return ProblemResponses.write(exchange, json, HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT",
                    "The service behind this route did not answer in time", traceId);
        }
        // Refused connections, and names that do not resolve (on Kubernetes, a
        // service with no endpoints), both mean "not reachable right now".
        if (causedBy(error, ConnectException.class) || causedBy(error, UnknownHostException.class)) {
            return ProblemResponses.write(exchange, json, HttpStatus.BAD_GATEWAY, "UPSTREAM_UNAVAILABLE",
                    "The service behind this route is not reachable right now", traceId);
        }
        if (error instanceof ResponseStatusException status) {
            HttpStatus resolved = HttpStatus.resolve(status.getStatusCode().value());
            HttpStatus code = resolved == null ? HttpStatus.INTERNAL_SERVER_ERROR : resolved;
            String detail = code == HttpStatus.NOT_FOUND ? "No route matches " + exchange.getRequest().getPath() : status.getReason();
            return ProblemResponses.write(exchange, json, code, code.name(), detail, traceId);
        }
        log.error("Unhandled gateway error on {} {}", exchange.getRequest().getMethod(), exchange.getRequest().getPath(), error);
        return ProblemResponses.write(exchange, json, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Something went wrong on our side", traceId);
    }

    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
