package io.github.amishpr.ledger.gateway;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Edge security. CORS, security headers and CSRF handling are always on. JWT
 * authentication is switched on with {@code gateway.jwt-enabled=true}: reads
 * then need the {@code ledger.read} scope and writes need {@code ledger.write}.
 *
 * <p>CSRF protection is off because the API is stateless: there are no cookies
 * or sessions for a forged request to ride on, and when JWT is on the token
 * travels in a header a third-party page cannot set.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
class SecurityConfig {

    private static final String[] ALWAYS_PUBLIC = {
        "/actuator/health/**", "/actuator/info", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
        "/openapi/**", "/webjars/**",
    };

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, GatewayProperties properties, JsonMapper json) {
        http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .cors(Customizer.withDefaults())
                .headers(headers -> headers
                        // Strict, apart from what Swagger UI needs from its own origin.
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'")));

        if (!properties.jwtEnabled()) {
            return http.authorizeExchange(exchange -> exchange.anyExchange().permitAll()).build();
        }

        return http
                .authorizeExchange(exchange -> exchange
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        .pathMatchers(ALWAYS_PUBLIC).permitAll()
                        // Browsers cannot set an Authorization header on a WebSocket
                        // handshake. The socket only carries notifications, never data
                        // a client could not already read; a ticket exchange is the
                        // next step if that changes.
                        .pathMatchers("/ws").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/**").hasAuthority("SCOPE_ledger.read")
                        .pathMatchers("/api/**").hasAuthority("SCOPE_ledger.write")
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((exchange, e) -> ProblemResponses.write(exchange, json,
                                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "A valid bearer token is required",
                                TraceIds.of(exchange)))
                        .accessDeniedHandler((exchange, e) -> ProblemResponses.write(exchange, json,
                                HttpStatus.FORBIDDEN, "FORBIDDEN", "The token does not carry the scope this needs",
                                TraceIds.of(exchange))))
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(GatewayProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "Authorization", "Idempotency-Key"));
        // A cross-origin fetch can only read response headers listed here.
        // Content-Disposition carries the CSV filename.
        cors.setExposedHeaders(List.of(
                "Content-Disposition", "Location", "Idempotent-Replayed", "Retry-After", "X-RateLimit-Remaining",
                TraceIdResponseFilter.HEADER));
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
