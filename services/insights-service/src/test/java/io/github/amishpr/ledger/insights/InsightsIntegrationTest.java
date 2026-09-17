package io.github.amishpr.ledger.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.amishpr.ledger.events.AccountCreated;
import io.github.amishpr.ledger.events.AccountSnapshot;
import io.github.amishpr.ledger.events.EntrySnapshot;
import io.github.amishpr.ledger.events.Topics;
import io.github.amishpr.ledger.events.TransactionPosted;
import io.github.amishpr.ledger.events.TransactionReversed;
import io.github.amishpr.ledger.events.TransactionSnapshot;
import io.github.amishpr.ledger.platform.json.IntegrationEventCodec;
import io.github.amishpr.ledger.testsupport.DatabaseCleaner;
import io.github.amishpr.ledger.testsupport.EmbeddedPostgresSupport;
import io.github.amishpr.ledger.testsupport.KafkaTopicProbe;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "ledger.kafka.consumer.initial-interval=10ms")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {Topics.TRANSACTIONS, InsightsIntegrationTest.DLT})
class InsightsIntegrationTest {

    static final String DLT = Topics.TRANSACTIONS + ".insights-service.dlt";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry, "insights_test");
    }

    @Autowired SpendingProjection projection;
    @Autowired SpendingQueries queries;
    @Autowired IntegrationEventCodec codec;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    private final UUID checking = UUID.randomUUID();
    private final UUID groceries = UUID.randomUUID();
    private final UUID dining = UUID.randomUUID();

    @BeforeEach
    void clean() {
        DatabaseCleaner.truncateAll(jdbc);
    }

    private TransactionSnapshot purchase(UUID expense, String name, long amount, String at) {
        UUID id = UUID.randomUUID();
        Instant when = Instant.parse(at);
        return new TransactionSnapshot(id, name, "POSTED", null, when, null, List.of(
                new EntrySnapshot(UUID.randomUUID(), id, expense, name, "EXPENSE", "DEBIT", amount, when),
                new EntrySnapshot(UUID.randomUUID(), id, checking, "Checking - Alex", "ASSET", "CREDIT", amount, when)));
    }

    @Test
    void countsOnlyDebitsToExpenseAccounts() {
        projection.apply(UUID.randomUUID(), TransactionPosted.TYPE, purchase(groceries, "Expenses - Groceries", 8_000, "2026-09-03T15:00:00Z"));
        projection.apply(UUID.randomUUID(), TransactionPosted.TYPE, purchase(dining, "Expenses - Dining", 2_500, "2026-09-10T19:00:00Z"));
        projection.apply(UUID.randomUUID(), TransactionPosted.TYPE, purchase(groceries, "Expenses - Groceries", 4_000, "2026-10-01T09:00:00Z"));

        assertThat(queries.byCategory()).extracting(SpendingQueries.CategoryTotal::accountName, SpendingQueries.CategoryTotal::totalMinor)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Expenses - Groceries", 12_000L),
                        org.assertj.core.groups.Tuple.tuple("Expenses - Dining", 2_500L));
        assertThat(queries.byMonth()).extracting(SpendingQueries.MonthTotal::month, SpendingQueries.MonthTotal::totalMinor)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("2026-09", 10_500L),
                        org.assertj.core.groups.Tuple.tuple("2026-10", 4_000L));
    }

    @Test
    void countsARedeliveredEventOnce() {
        UUID eventId = UUID.randomUUID();
        TransactionSnapshot groceriesRun = purchase(groceries, "Expenses - Groceries", 8_000, "2026-09-03T15:00:00Z");

        assertThat(projection.apply(eventId, TransactionPosted.TYPE, groceriesRun)).isTrue();
        assertThat(projection.apply(eventId, TransactionPosted.TYPE, groceriesRun)).isFalse();

        assertThat(queries.byCategory()).singleElement().extracting(SpendingQueries.CategoryTotal::totalMinor).isEqualTo(8_000L);
    }

    @Test
    void bucketsByTheUtcMonth() {
        // 11:30pm on Jan 31 in New York is already February in UTC.
        projection.apply(UUID.randomUUID(), TransactionPosted.TYPE, purchase(dining, "Expenses - Dining", 900, "2026-02-01T04:30:00Z"));

        assertThat(queries.byMonth()).extracting(SpendingQueries.MonthTotal::month).containsExactly("2026-02");
    }

    @Test
    void buildsTheReadModelFromKafkaAndServesItWithCentsAsStrings() throws Exception {
        TransactionSnapshot groceriesRun = purchase(groceries, "Expenses - Groceries", 8_000, "2026-09-03T15:00:00Z");
        TransactionPosted posted = TransactionPosted.of(groceriesRun, List.of(groceries, checking), Instant.now());
        String json = codec.write(posted);

        kafka.send(Topics.TRANSACTIONS, groceriesRun.id().toString(), json).get();
        kafka.send(Topics.TRANSACTIONS, groceriesRun.id().toString(), json).get(); // redelivery
        kafka.send(Topics.TRANSACTIONS, "acct", codec.write(AccountCreated.of(
                new AccountSnapshot(UUID.randomUUID(), "Not a transaction", "ASSET", "USD", Instant.now()), Instant.now()))).get();
        TransactionSnapshot reversal = purchase(dining, "Expenses - Dining", 1_200, "2026-09-04T12:00:00Z");
        TransactionReversed reversed = TransactionReversed.of(reversal, UUID.randomUUID(), List.of(), Instant.now());
        kafka.send(Topics.TRANSACTIONS, reversal.id().toString(), codec.write(reversed)).get();

        // The duplicate and the unrelated event leave no row; only these two do.
        awaitProcessed(List.of(posted.eventId(), reversed.eventId()), Duration.ofSeconds(20));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event", Integer.class)).isEqualTo(2);

        mvc.perform(get("/api/v1/insights/spending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byCategory[0].accountName").value("Expenses - Groceries"))
                .andExpect(jsonPath("$.byCategory[0].totalMinor").value("8000"))
                .andExpect(jsonPath("$.byMonth[0].month").value("2026-09"))
                .andExpect(jsonPath("$.byMonth[0].totalMinor").value("9200"));
    }

    @Test
    void sendsAPoisonMessageToTheDeadLetterTopicAndKeepsGoing() throws Exception {
        kafka.send(Topics.TRANSACTIONS, "poison", "{\"eventType\":\"transaction.posted\",\"transaction\":").get();
        TransactionSnapshot healthy = purchase(groceries, "Expenses - Groceries", 500, "2026-09-03T15:00:00Z");
        TransactionPosted posted = TransactionPosted.of(healthy, List.of(), Instant.now());
        kafka.send(Topics.TRANSACTIONS, healthy.id().toString(), codec.write(posted)).get();

        try (KafkaTopicProbe dlt = KafkaTopicProbe.subscribe(broker, DLT)) {
            dlt.await(r -> "poison".equals(r.key()), Duration.ofSeconds(20));
        }
        awaitProcessed(List.of(posted.eventId()), Duration.ofSeconds(20));
    }

    private void awaitProcessed(List<UUID> eventIds, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Integer seen = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM processed_event WHERE event_id = ANY (?)", Integer.class,
                    (Object) eventIds.toArray(UUID[]::new));
            if (seen != null && seen == eventIds.size()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Events " + eventIds + " were not all processed within " + timeout);
    }
}
