package io.github.amishpr.ledger.testsupport;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * A real Postgres for integration tests, started once per test JVM from
 * embedded binaries. It needs no Docker and no local install, which keeps
 * {@code ./mvnw verify} down to a single requirement: a JDK.
 *
 * <p>Each service gets its own database inside the shared server, the same
 * way each service owns its own database in production.
 *
 * <pre>{@code
 * @DynamicPropertySource
 * static void database(DynamicPropertyRegistry registry) {
 *     EmbeddedPostgresSupport.register(registry, "ledger");
 * }
 * }</pre>
 */
public final class EmbeddedPostgresSupport {

    private static EmbeddedPostgres server;
    private static final Set<String> databases = new HashSet<>();

    private EmbeddedPostgresSupport() {}

    /** Points Spring's datasource at a database with this name, creating it the first time. */
    public static void register(DynamicPropertyRegistry registry, String database) {
        String url = jdbcUrl(database);
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    /** JDBC URL for a database with this name, creating it the first time it is asked for. */
    public static synchronized String jdbcUrl(String database) {
        EmbeddedPostgres postgres = server();
        if (databases.add(database)) {
            try (Connection connection = postgres.getPostgresDatabase().getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + database);
                statement.execute("CREATE DATABASE " + database);
            } catch (SQLException e) {
                throw new IllegalStateException("Could not create test database " + database, e);
            }
        }
        return postgres.getJdbcUrl("postgres", database);
    }

    private static EmbeddedPostgres server() {
        if (server == null) {
            try {
                server = EmbeddedPostgres.builder()
                        // Matches production closely enough to catch lock and
                        // isolation behaviour, while staying fast to start.
                        .setServerConfig("max_connections", "200")
                        .setServerConfig("fsync", "off")
                        .setServerConfig("synchronous_commit", "off")
                        .start();
            } catch (IOException e) {
                throw new UncheckedIOException("Could not start embedded Postgres", e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.close();
                } catch (IOException ignored) {
                    // The JVM is exiting anyway.
                }
            }));
        }
        return server;
    }
}
