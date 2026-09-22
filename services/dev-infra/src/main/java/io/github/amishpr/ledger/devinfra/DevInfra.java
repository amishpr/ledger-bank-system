package io.github.amishpr.ledger.devinfra;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

/**
 * Postgres and Kafka for local development, in one process, with nothing to
 * install but a JDK. It is the same pair of things deploy/docker-compose.yml
 * starts, on the same ports and with the same credentials, so the services
 * cannot tell the two apart.
 *
 * <pre>
 *   java -jar dev-infra/target/dev-infra-1.0.0-SNAPSHOT.jar [--clean]
 * </pre>
 *
 * Postgres keeps its data in {@code .dev-data/postgres} between runs, the way
 * the old SQLite file did. {@code --clean} wipes it, so the demo seed runs
 * again. Kafka starts empty every time; the services rebuild what they need.
 */
public final class DevInfra {

    private static final List<String> DATABASES = List.of("ledger", "recurring", "insights");

    public static void main(String[] args) throws Exception {
        boolean clean = List.of(args).contains("--clean");
        int postgresPort = Integer.parseInt(System.getenv().getOrDefault("DEV_POSTGRES_PORT", "5432"));
        int kafkaPort = Integer.parseInt(System.getenv().getOrDefault("DEV_KAFKA_PORT", "9092"));
        Path dataDir = Path.of(System.getenv().getOrDefault("DEV_DATA_DIR", ".dev-data")).toAbsolutePath();
        Path postgresDir = dataDir.resolve("postgres");

        if (clean) {
            deleteRecursively(postgresDir);
        }
        Files.createDirectories(postgresDir);
        boolean fresh = !Files.exists(postgresDir.resolve("PG_VERSION"));

        EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setPort(postgresPort)
                .setDataDirectory(postgresDir.toFile())
                .setCleanDataDirectory(false)
                .setServerConfig("max_connections", "200")
                .start();
        createRoleAndDatabases(postgres);

        // The KRaft test broker always listens on a random port (it ignores
        // kafkaPorts() and the listeners setting), so a forwarder puts it on
        // the usual one.
        EmbeddedKafkaKraftBroker kafka = new EmbeddedKafkaKraftBroker(1, 3);
        kafka.brokerProperty("auto.create.topics.enable", "true");
        kafka.afterPropertiesSet();
        int brokerPort = Integer.parseInt(kafka.getBrokersAsString().replaceAll(".*:", ""));
        TcpForwarder forwarder = new TcpForwarder(kafkaPort, brokerPort);

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nStopping Kafka and Postgres...");
            try {
                forwarder.close();
            } catch (IOException ignored) {
                // Exiting anyway.
            }
            kafka.destroy();
            try {
                postgres.close();
            } catch (IOException ignored) {
                // Exiting anyway.
            }
            stopped.countDown();
        }));

        System.out.printf("""

                Ledger dev infrastructure is up.
                  Postgres  localhost:%d   user ledger / ledger   databases %s%s
                  Kafka     localhost:%d   KRaft, single broker
                  Data      %s

                Leave this running and start the services. Ctrl+C stops both.
                %n""",
                postgresPort,
                String.join(", ", DATABASES),
                fresh ? "   (fresh)" : "   (kept from last run, --clean to reset)",
                kafkaPort,
                postgresDir);
        stopped.await();
    }

    private static void createRoleAndDatabases(EmbeddedPostgres postgres) throws SQLException {
        try (Connection connection = postgres.getPostgresDatabase().getConnection();
                Statement statement = connection.createStatement()) {
            if (!exists(connection, "SELECT 1 FROM pg_roles WHERE rolname = ?", "ledger")) {
                statement.execute("CREATE ROLE ledger LOGIN PASSWORD 'ledger'");
            }
            for (String database : DATABASES) {
                if (!exists(connection, "SELECT 1 FROM pg_database WHERE datname = ?", database)) {
                    statement.execute("CREATE DATABASE " + database + " OWNER ledger");
                }
            }
        }
    }

    private static boolean exists(Connection connection, String sql, String name) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, name);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }
}
