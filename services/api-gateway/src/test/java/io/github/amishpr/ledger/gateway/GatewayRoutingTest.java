package io.github.amishpr.ledger.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gateway.rate-limit.capacity=3", "gateway.rate-limit.refill-per-second=1"})
class GatewayRoutingTest {

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        StubUpstreams.register(registry);
    }

    @LocalServerPort int port;
    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void routesEachPathToItsServiceAndPropagatesTheTrace() {
        client.get().uri("/api/v1/accounts").exchange()
                .expectStatus().isOk()
                .expectHeader().exists("X-Trace-Id")
                .expectBody()
                .jsonPath("$.service").isEqualTo("ledger")
                .jsonPath("$.path").isEqualTo("/api/v1/accounts")
                .jsonPath("$.traceparent").isEqualTo(true);

        client.get().uri("/api/v1/transactions/abc").exchange()
                .expectBody().jsonPath("$.service").isEqualTo("ledger");
        client.get().uri("/api/v1/recurring-transfers").exchange()
                .expectBody().jsonPath("$.service").isEqualTo("recurring");
        client.get().uri("/openapi/ledger-service").exchange()
                .expectBody().jsonPath("$.path").isEqualTo("/v3/api-docs");
    }

    @Test
    void answersCorsPreflightsForTheDashboardOnly() {
        client.options().uri("/api/v1/transactions")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type, Idempotency-Key")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173");

        client.options().uri("/api/v1/transactions")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange()
                .expectStatus().isForbidden();

        client.get().uri("/api/v1/accounts").header(HttpHeaders.ORIGIN, "http://localhost:5173").exchange()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173")
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, exposed -> assertThat(exposed)
                        .contains("Content-Disposition").contains("X-Trace-Id"));
    }

    @Test
    void reportsAnUnknownRouteAsProblemDetails() {
        client.get().uri("/api/v2/whatever").exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void reportsADownServiceAsABadGateway() {
        client.get().uri("/api/v1/insights/spending").exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.code").isEqualTo("UPSTREAM_UNAVAILABLE")
                .jsonPath("$.traceId").exists();
    }

    @Test
    void rateLimitsBurstsOfWritesButNotReads() {
        for (int i = 0; i < 3; i++) {
            client.post().uri("/api/v1/recurring-transfers").exchange().expectStatus().isCreated();
        }
        client.post().uri("/api/v1/recurring-transfers").exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "1")
                .expectBody().jsonPath("$.code").isEqualTo("RATE_LIMITED");

        for (int i = 0; i < 10; i++) {
            client.get().uri("/api/v1/recurring-transfers").exchange().expectStatus().isOk();
        }
    }

    @Test
    void proxiesTheLiveUpdatesWebSocket() throws Exception {
        CompletableFuture<String> first = new CompletableFuture<>();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        first.complete(data.toString());
                        return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);

        assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo("{\"type\":\"connected\"}");
        socket.abort();
    }

    @Test
    void servesOneSwaggerUiForEveryService() {
        client.get().uri("/v3/api-docs/swagger-config").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.urls.length()").isEqualTo(3)
                .jsonPath("$.urls[*].url").value(urls -> assertThat(urls.toString())
                        .contains("ledger-service", "recurring-transfer-service", "insights-service"))
                .jsonPath("$['urls.primaryName']").isEqualTo("Ledger service");
    }
}
