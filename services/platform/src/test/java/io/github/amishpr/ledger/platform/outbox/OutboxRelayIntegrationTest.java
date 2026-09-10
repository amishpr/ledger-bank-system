package io.github.amishpr.ledger.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.amishpr.ledger.events.AccountCreated;
import io.github.amishpr.ledger.events.AccountSnapshot;
import io.github.amishpr.ledger.events.IntegrationEvent;
import io.github.amishpr.ledger.events.Topics;
import io.github.amishpr.ledger.platform.PlatformTestApplication;
import io.github.amishpr.ledger.platform.json.IntegrationEventCodec;
import io.github.amishpr.ledger.testsupport.DatabaseCleaner;
import io.github.amishpr.ledger.testsupport.EmbeddedPostgresSupport;
import io.github.amishpr.ledger.testsupport.KafkaTopicProbe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        classes = PlatformTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.application.name=platform-test",
            "ledger.outbox.enabled=true",
            // The test drives the relay by hand instead of racing its timer.
            "ledger.outbox.poll-interval=1h",
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:outbox-schema.sql",
        })
@EmbeddedKafka(partitions = 1, topics = Topics.ACCOUNTS)
class OutboxRelayIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry, "platform_outbox");
    }

    @Autowired Outbox outbox;
    @Autowired OutboxRelay relay;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired IntegrationEventCodec codec;
    @Autowired EmbeddedKafkaBroker broker;

    @BeforeEach
    void clean() {
        DatabaseCleaner.truncateAll(jdbc);
    }

    private static AccountCreated accountCreated(String name) {
        return AccountCreated.of(
                new AccountSnapshot(UUID.randomUUID(), name, "ASSET", "USD", Instant.now()), Instant.now());
    }

    @Test
    void publishesACommittedEventWithItsKeyAndHeaders() {
        AccountCreated event = accountCreated("Checking - Alex");
        transactions.executeWithoutResult(status -> outbox.append(Topics.ACCOUNTS, event));

        assertThat(relay.pendingCount()).isEqualTo(1);
        assertThat(relay.relayPending()).isEqualTo(1);
        assertThat(relay.pendingCount()).isZero();

        try (KafkaTopicProbe probe = KafkaTopicProbe.subscribe(broker, Topics.ACCOUNTS)) {
            ConsumerRecord<String, String> record =
                    probe.await(r -> r.key().equals(event.aggregateId()), Duration.ofSeconds(10));
            IntegrationEvent read = codec.read(record.value());
            assertThat(read).isEqualTo(event);
            assertThat(new String(record.headers().lastHeader(Topics.HEADER_EVENT_TYPE).value(), StandardCharsets.UTF_8))
                    .isEqualTo(AccountCreated.TYPE);
            assertThat(new String(record.headers().lastHeader(Topics.HEADER_EVENT_ID).value(), StandardCharsets.UTF_8))
                    .isEqualTo(event.eventId().toString());
        }
    }

    @Test
    void neverPublishesAnEventWhoseTransactionRolledBack() {
        AccountCreated event = accountCreated("Never happened");

        transactions.executeWithoutResult(status -> {
            outbox.append(Topics.ACCOUNTS, event);
            status.setRollbackOnly();
        });

        assertThat(relay.pendingCount()).isZero();
        assertThat(relay.relayPending()).isZero();
    }

    @Test
    void refusesToWriteOutsideATransaction() {
        assertThatThrownBy(() -> outbox.append(Topics.ACCOUNTS, accountCreated("Orphan")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void publishesInSeveralBatchesWhenThereIsABacklog() {
        transactions.executeWithoutResult(status -> {
            for (int i = 0; i < 250; i++) {
                outbox.append(Topics.ACCOUNTS, accountCreated("Account " + i));
            }
        });

        assertThat(relay.relayPending()).isEqualTo(250);
        assertThat(relay.pendingCount()).isZero();
    }

    @Test
    void purgesOnlyRowsPublishedBeforeTheRetentionWindow() {
        transactions.executeWithoutResult(status -> {
            outbox.append(Topics.ACCOUNTS, accountCreated("Old"));
            outbox.append(Topics.ACCOUNTS, accountCreated("Recent"));
            outbox.append(Topics.ACCOUNTS, accountCreated("Waiting"));
        });
        relay.relayPending();
        jdbc.update("UPDATE outbox_event SET published_at = now() - interval '30 days' WHERE payload->'account'->>'name' = 'Old'");
        jdbc.update("UPDATE outbox_event SET published_at = NULL WHERE payload->'account'->>'name' = 'Waiting'");

        assertThat(relay.purgePublished()).isEqualTo(1);
        List<String> left = jdbc.queryForList("SELECT payload->'account'->>'name' FROM outbox_event ORDER BY seq", String.class);
        assertThat(left).containsExactly("Recent", "Waiting");
    }
}
