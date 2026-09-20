package io.github.amishpr.ledger.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.amishpr.ledger.events.AccountCreated;
import io.github.amishpr.ledger.events.AccountSnapshot;
import io.github.amishpr.ledger.events.RecurringTransferExecuted;
import io.github.amishpr.ledger.events.RecurringTransferFailed;
import io.github.amishpr.ledger.events.Topics;
import io.github.amishpr.ledger.platform.json.IntegrationEventCodec;
import io.github.amishpr.ledger.recurring.application.AccountDirectory;
import io.github.amishpr.ledger.recurring.application.RecurringTransferService;
import io.github.amishpr.ledger.recurring.application.RecurringTransferService.NewSchedule;
import io.github.amishpr.ledger.recurring.application.RecurringTransferSweeper;
import io.github.amishpr.ledger.recurring.client.LedgerClient;
import io.github.amishpr.ledger.recurring.client.LedgerGateway;
import io.github.amishpr.ledger.recurring.client.LedgerGateway.PostingOutcome;
import io.github.amishpr.ledger.recurring.domain.RecurrenceInterval;
import io.github.amishpr.ledger.recurring.domain.RecurringTransfer;
import io.github.amishpr.ledger.recurring.domain.RunStatus;
import io.github.amishpr.ledger.recurring.repository.RecurringTransferRepository;
import io.github.amishpr.ledger.testsupport.DatabaseCleaner;
import io.github.amishpr.ledger.testsupport.EmbeddedPostgresSupport;
import io.github.amishpr.ledger.testsupport.KafkaTopicProbe;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The cases from the original server/src/ledger/recurringService.test.ts, plus
 * what the split into services adds: transient failures, the distributed lock,
 * account replication, and events. The ledger is mocked at the gateway, which
 * has its own tests for the HTTP side.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {Topics.ACCOUNTS, Topics.RECURRING_TRANSFERS})
class RecurringIntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry, "recurring_test");
    }

    @MockitoBean LedgerGateway ledger;

    @Autowired RecurringTransferService service;
    @Autowired RecurringTransferSweeper sweeper;
    @Autowired RecurringTransferRepository repository;
    @Autowired AccountDirectory accounts;
    @Autowired IntegrationEventCodec codec;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    private final UUID checking = UUID.randomUUID();
    private final UUID savings = UUID.randomUUID();
    private final UUID groceries = UUID.randomUUID();
    private final Instant due = Instant.parse("2026-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        DatabaseCleaner.truncateAll(jdbc);
        accounts.replicate(checking, "Checking - Alex", "ASSET", "USD", due);
        accounts.replicate(savings, "Savings - Alex", "ASSET", "USD", due);
        accounts.replicate(groceries, "Expenses - Groceries", "EXPENSE", "USD", due);
    }

    private RecurringTransfer schedule(RecurrenceInterval interval) {
        return service.create(new NewSchedule("Weekly savings sweep", checking, savings, 1_000, interval, due)).transfer();
    }

    private static PostingOutcome posted(String key) {
        UUID tx = UUID.randomUUID();
        return new PostingOutcome.Posted(new LedgerClient.PostResult(
                new LedgerClient.Transaction(tx, "Weekly savings sweep", "POSTED", key, Instant.now(), List.of()),
                false,
                List.of()));
    }

    @Test
    void executesADueTransferAndAdvancesNextRunAt() {
        RecurringTransfer transfer = schedule(RecurrenceInterval.WEEKLY);
        when(ledger.post(anyString(), any())).thenAnswer(call -> posted(call.getArgument(0)));

        List<RecurringTransferSweeper.SweepResult> results = sweeper.sweep(due);

        assertThat(results).singleElement().satisfies(r -> assertThat(r.outcome()).isEqualTo("posted"));
        RecurringTransfer updated = repository.findById(transfer.getId()).orElseThrow();
        assertThat(updated.getLastRunStatus()).isEqualTo(RunStatus.SUCCESS);
        assertThat(updated.getNextRunAt()).isEqualTo(Instant.parse("2026-01-08T00:00:00Z"));

        ArgumentCaptor<LedgerClient.PostRequest> request = ArgumentCaptor.forClass(LedgerClient.PostRequest.class);
        verify(ledger).post(eq("recurring:" + transfer.getId() + ":2026-01-01T00:00:00Z"), request.capture());
        assertThat(request.getValue().entries()).extracting(LedgerClient.Entry::direction).containsExactly("CREDIT", "DEBIT");
        assertThat(request.getValue().origin().reference()).isEqualTo(transfer.getId().toString());

        try (KafkaTopicProbe probe = KafkaTopicProbe.subscribe(broker, Topics.RECURRING_TRANSFERS)) {
            var record = probe.await(r -> r.key().equals(transfer.getId().toString()), Duration.ofSeconds(15));
            assertThat(codec.read(record.value())).isInstanceOf(RecurringTransferExecuted.class);
        }
    }

    @Test
    void sendsTheSameKeyWhenAnOccurrenceIsSweptTwice() {
        RecurringTransfer transfer = schedule(RecurrenceInterval.DAILY);
        when(ledger.post(anyString(), any())).thenAnswer(call -> posted(call.getArgument(0)));

        sweeper.sweep(due);
        // Two overlapping sweeps both seeing the same due occurrence.
        jdbc.update("UPDATE recurring_transfer SET next_run_at = ?", Timestamp.from(due));
        sweeper.sweep(due);

        // The ledger turns the second into a replay, because the key is identical.
        verify(ledger, times(2)).post(eq(transfer.occurrenceKey()), any());
    }

    @Test
    void recordsARejectionAndStillAdvancesInsteadOfRetryingForever() {
        RecurringTransfer transfer = schedule(RecurrenceInterval.DAILY);
        when(ledger.post(anyString(), any()))
                .thenReturn(new PostingOutcome.Rejected("INSUFFICIENT_FUNDS", "Account x has insufficient funds"));

        assertThat(sweeper.sweep(due)).singleElement().satisfies(r -> {
            assertThat(r.outcome()).isEqualTo("rejected");
            assertThat(r.error()).contains("insufficient funds");
        });

        RecurringTransfer updated = repository.findById(transfer.getId()).orElseThrow();
        assertThat(updated.getLastRunStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(updated.getNextRunAt()).isAfter(due);

        try (KafkaTopicProbe probe = KafkaTopicProbe.subscribe(broker, Topics.RECURRING_TRANSFERS)) {
            var record = probe.await(r -> r.key().equals(transfer.getId().toString()), Duration.ofSeconds(15));
            assertThat(codec.read(record.value())).isInstanceOfSatisfying(RecurringTransferFailed.class,
                    failed -> assertThat(failed.errorCode()).isEqualTo("INSUFFICIENT_FUNDS"));
        }
    }

    @Test
    void keepsAnOccurrenceDueWhenTheLedgerIsDown() {
        RecurringTransfer transfer = schedule(RecurrenceInterval.DAILY);
        when(ledger.post(anyString(), any())).thenReturn(new PostingOutcome.Unavailable("The ledger did not respond"));

        sweeper.sweep(due);

        RecurringTransfer updated = repository.findById(transfer.getId()).orElseThrow();
        assertThat(updated.getNextRunAt()).isEqualTo(due);
        assertThat(updated.getLastRunError()).contains("did not respond");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class)).isZero();
    }

    @Test
    void skipsAPausedSchedule() {
        RecurringTransfer transfer = schedule(RecurrenceInterval.DAILY);
        service.toggle(transfer.getId());

        assertThat(sweeper.sweep(due)).isEmpty();
        verify(ledger, never()).post(anyString(), any());
    }

    @Test
    void onlyOneOfTwoConcurrentSweepsRuns() throws Exception {
        schedule(RecurrenceInterval.DAILY);
        jdbc.update("UPDATE recurring_transfer SET next_run_at = now() - interval '1 minute'");
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(ledger.post(anyString(), any())).thenAnswer(call -> {
            inside.countDown();
            release.await();
            return posted(call.getArgument(0));
        });

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            pool.submit(sweeper::scheduledSweep);
            inside.await();
            // The first sweep holds the ShedLock lock, so this one returns at once.
            sweeper.scheduledSweep();
            release.countDown();
        }

        verify(ledger, times(1)).post(anyString(), any());
    }

    @Test
    void replicatesAccountsFromKafkaSoCreatingNeedsNoLedgerCall() throws Exception {
        UUID jordan = UUID.randomUUID();
        AccountCreated created = AccountCreated.of(
                new AccountSnapshot(jordan, "Checking - Jordan", "ASSET", "USD", due), Instant.now());
        kafka.send(Topics.ACCOUNTS, jordan.toString(), codec.write(created)).get();

        long deadline = System.currentTimeMillis() + 15_000;
        while (accounts.findAll(List.of(jordan)).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        service.create(new NewSchedule("Rent split", checking, jordan, 12_000, RecurrenceInterval.MONTHLY, null));

        verify(ledger, never()).findAccount(any());
    }

    @Test
    void fallsBackToTheLedgerForAnAccountItHasNotSeenYet() throws Exception {
        UUID brandNew = UUID.randomUUID();
        when(ledger.findAccount(brandNew)).thenReturn(Optional.of(
                new LedgerClient.Account(brandNew, "Savings - Jordan", "ASSET", "USD", due)));

        mvc.perform(post("/api/v1/recurring-transfers").contentType(MediaType.APPLICATION_JSON).content("""
                        {"description":"Save","fromAccountId":"%s","toAccountId":"%s","amountMinor":"2500","interval":"WEEKLY"}
                        """.formatted(checking, brandNew)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.toAccount.name").value("Savings - Jordan"));
    }

    @Test
    void validatesNewSchedulesLikeTheOriginalApi() throws Exception {
        String body = """
                {"description":"Bad","fromAccountId":"%s","toAccountId":"%s","amountMinor":"%s","interval":"DAILY"}
                """;
        mvc.perform(post("/api/v1/recurring-transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(checking, checking, "100")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_RECURRING_TRANSFER"));
        mvc.perform(post("/api/v1/recurring-transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(checking, groceries, "100")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_RECURRING_TRANSFER"));
        mvc.perform(post("/api/v1/recurring-transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(checking, savings, "0")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));

        UUID unknown = UUID.randomUUID();
        when(ledger.findAccount(unknown)).thenReturn(Optional.empty());
        mvc.perform(post("/api/v1/recurring-transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(checking, unknown, "100")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        UUID unreachable = UUID.randomUUID();
        when(ledger.findAccount(unreachable)).thenThrow(new LedgerGateway.LedgerUnavailableException("down", null));
        mvc.perform(post("/api/v1/recurring-transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(checking, unreachable, "100")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("LEDGER_UNAVAILABLE"));
    }

    @Test
    void servesTheShapeTheDashboardExpectsAndSupportsPauseResumeAndDelete() throws Exception {
        RecurringTransfer transfer = schedule(RecurrenceInterval.WEEKLY);
        String path = "/api/v1/recurring-transfers/" + transfer.getId();

        mvc.perform(get("/api/v1/recurring-transfers"))
                .andExpect(jsonPath("$[0].fromAccount.name").value("Checking - Alex"))
                .andExpect(jsonPath("$[0].toAccount.name").value("Savings - Alex"))
                .andExpect(jsonPath("$[0].amountMinor").value("1000"))
                .andExpect(jsonPath("$[0].interval").value("WEEKLY"))
                .andExpect(jsonPath("$[0].lastRunStatus").isEmpty());

        mvc.perform(post(path + "/toggle-active")).andExpect(jsonPath("$.active").value(false));
        mvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(jsonPath("$.active").value(true));
        mvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(jsonPath("$.active").value(true));

        mvc.perform(delete(path)).andExpect(status().isNoContent());
        mvc.perform(get(path)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECURRING_TRANSFER_NOT_FOUND"));
        assertThat(repository.findById(transfer.getId())).isEmpty();
    }
}
