package io.github.amishpr.ledger.accounting;

import io.github.amishpr.ledger.events.Topics;
import io.github.amishpr.ledger.testsupport.DatabaseCleaner;
import io.github.amishpr.ledger.testsupport.EmbeddedPostgresSupport;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for tests that need the whole service: real Postgres (embedded
 * binaries, no Docker) and a real Kafka broker in the test JVM. Every
 * subclass shares one Spring context, so the cost of starting it is paid once.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {Topics.ACCOUNTS, Topics.TRANSACTIONS})
public abstract class IntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry, "ledger_test");
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        DatabaseCleaner.truncateAll(jdbc);
    }
}
