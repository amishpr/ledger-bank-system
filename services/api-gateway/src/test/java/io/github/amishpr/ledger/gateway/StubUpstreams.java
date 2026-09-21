package io.github.amishpr.ledger.gateway;

import java.util.List;
import org.springframework.test.context.DynamicPropertyRegistry;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * Small fake services the gateway routes to, started once per test JVM. Each
 * answers with its own name and echoes what it received, which is all a
 * routing test needs to know.
 */
final class StubUpstreams {

    static final DisposableServer LEDGER = service("ledger");
    static final DisposableServer RECURRING = service("recurring");
    static final DisposableServer NOTIFICATIONS = HttpServer.create()
            .port(0)
            .route(routes -> routes.ws("/ws", (in, out) -> out.sendString(Mono.just("{\"type\":\"connected\"}"))))
            .bindNow();

    static final int CLOSED_PORT = freePort();

    private StubUpstreams() {}

    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static DisposableServer service(String name) {
        return HttpServer.create()
                .port(0)
                .route(routes -> routes
                        .route(request -> true, (request, response) -> response
                                .header("Content-Type", "application/json")
                                .status(request.method().name().equals("POST") ? 201 : 200)
                                .sendString(Mono.just("""
                                        {"service":"%s","method":"%s","path":"%s","traceparent":%s}
                                        """.formatted(
                                        name,
                                        request.method().name(),
                                        request.uri(),
                                        request.requestHeaders().contains("traceparent") ? "true" : "false")))))
                .bindNow();
    }

    static void register(DynamicPropertyRegistry registry) {
        registry.add("LEDGER_SERVICE_URL", () -> "http://localhost:" + LEDGER.port());
        registry.add("RECURRING_SERVICE_URL", () -> "http://localhost:" + RECURRING.port());
        // Nothing listens here, which is the point: it stands in for a service that is down.
        registry.add("INSIGHTS_SERVICE_URL", () -> "http://localhost:" + CLOSED_PORT);
        registry.add("NOTIFICATION_SERVICE_WS_URL", () -> "ws://localhost:" + NOTIFICATIONS.port());
    }

    static List<DisposableServer> all() {
        return List.of(LEDGER, RECURRING, NOTIFICATIONS);
    }
}
