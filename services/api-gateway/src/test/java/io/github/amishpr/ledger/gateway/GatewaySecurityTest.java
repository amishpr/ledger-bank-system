package io.github.amishpr.ledger.gateway;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * JWT mode. A real issuer is replaced by a decoder that knows two tokens, so
 * the test exercises the actual filter chain and scope rules end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "gateway.jwt-enabled=true")
@Import(GatewaySecurityTest.Tokens.class)
class GatewaySecurityTest {

    @TestConfiguration
    static class Tokens {
        @Bean
        ReactiveJwtDecoder jwtDecoder() {
            return token -> switch (token) {
                case "reader" -> Mono.just(jwt(token, "ledger.read"));
                case "writer" -> Mono.just(jwt(token, "ledger.read ledger.write"));
                default -> Mono.error(new BadJwtException("Unknown test token"));
            };
        }

        private static Jwt jwt(String token, String scope) {
            return new Jwt(token, Instant.now(), Instant.now().plusSeconds(300), Map.of("alg", "none"),
                    Map.of("sub", token, "scope", scope));
        }
    }

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
    void rejectsARequestWithoutATokenAsProblemDetails() {
        client.get().uri("/api/v1/accounts").exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHENTICATED");
        client.get().uri("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer forged").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void readScopeCanReadButNotWrite() {
        client.get().uri("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer reader").exchange()
                .expectStatus().isOk();
        client.post().uri("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer reader").exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
    }

    @Test
    void writeScopeCanWrite() {
        client.post().uri("/api/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer writer").exchange()
                .expectStatus().isCreated();
    }

    @Test
    void keepsHealthAndApiDocsPublic() {
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
        client.get().uri("/v3/api-docs/swagger-config").exchange().expectStatus().isOk();
    }
}
