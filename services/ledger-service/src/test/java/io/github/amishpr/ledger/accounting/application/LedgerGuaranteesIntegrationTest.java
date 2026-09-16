package io.github.amishpr.ledger.accounting.application;

import static io.github.amishpr.ledger.accounting.domain.EntryDirection.CREDIT;
import static io.github.amishpr.ledger.accounting.domain.EntryDirection.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.amishpr.ledger.accounting.IntegrationTest;
import io.github.amishpr.ledger.accounting.domain.Account;
import io.github.amishpr.ledger.accounting.domain.AccountType;
import io.github.amishpr.ledger.accounting.domain.LedgerException;
import io.github.amishpr.ledger.accounting.domain.Origin;
import io.github.amishpr.ledger.accounting.domain.OriginType;
import io.github.amishpr.ledger.accounting.domain.PostingLine;
import io.github.amishpr.ledger.events.IntegrationEvent;
import io.github.amishpr.ledger.events.Topics;
import io.github.amishpr.ledger.events.TransactionPosted;
import io.github.amishpr.ledger.events.TransactionReversed;
import io.github.amishpr.ledger.platform.json.IntegrationEventCodec;
import io.github.amishpr.ledger.testsupport.KafkaTopicProbe;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The properties the ledger promises, tested the hard way: under real
 * concurrency, against real Postgres locks and triggers, and through a real
 * Kafka broker.
 */
class LedgerGuaranteesIntegrationTest extends IntegrationTest {

    @Autowired AccountService accounts;
    @Autowired PostingService posting;
    @Autowired IntegrationEventCodec codec;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired MockMvc mvc;

    private static List<PostingLine> move(Account from, Account to, long amount) {
        return List.of(new PostingLine(to.getId(), DEBIT, amount), new PostingLine(from.getId(), CREDIT, amount));
    }

    @Test
    void concurrentWithdrawalsNeverTakeAnAccountNegative() throws Exception {
        Account equity = accounts.open("Equity", AccountType.EQUITY, "USD");
        Account checking = accounts.open("Checking", AccountType.ASSET, "USD");
        Account spending = accounts.open("Spending", AccountType.EXPENSE, "USD");
        posting.post(new PostTransactionCommand("Opening balance", move(equity, checking, 1_000)));

        // Twenty requests for 100 each race for 1,000. Without row locks
        // several would read the same balance and all pass the check.
        List<Callable<Boolean>> withdrawals = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            withdrawals.add(() -> {
                try {
                    posting.post(new PostTransactionCommand("Withdrawal", move(checking, spending, 100)));
                    return true;
                } catch (LedgerException e) {
                    assertThat(e.code()).isEqualTo("INSUFFICIENT_FUNDS");
                    return false;
                }
            });
        }
        List<Boolean> outcomes = runAllAtOnce(withdrawals);

        assertThat(outcomes.stream().filter(ok -> ok).count()).isEqualTo(10);
        assertThat(accounts.get(checking.getId()).balanceMinor()).isZero();
        assertThat(accounts.get(spending.getId()).balanceMinor()).isEqualTo(1_000);
    }

    @Test
    void racingRequestsWithTheSameKeyPostExactlyOnce() throws Exception {
        Account equity = accounts.open("Equity", AccountType.EQUITY, "USD");
        Account checking = accounts.open("Checking", AccountType.ASSET, "USD");
        PostTransactionCommand command =
                new PostTransactionCommand("Opening balance", move(equity, checking, 500), "double-click", null);

        List<Callable<PostingResult>> clicks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            clicks.add(() -> posting.post(command));
        }
        List<PostingResult> results = runAllAtOnce(clicks);

        Set<UUID> ids = results.stream().map(r -> r.transaction().getId()).collect(Collectors.toSet());
        assertThat(ids).hasSize(1);
        assertThat(results.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
        assertThat(accounts.get(checking.getId()).balanceMinor()).isEqualTo(500);
    }

    @Test
    void theDatabaseItselfRefusesToEditOrDeletePostedEntries() {
        Account equity = accounts.open("Equity", AccountType.EQUITY, "USD");
        Account checking = accounts.open("Checking", AccountType.ASSET, "USD");
        UUID tx = posting.post(new PostTransactionCommand("Opening balance", move(equity, checking, 500)))
                .transaction().getId();

        assertThatThrownBy(() -> jdbc.update("UPDATE journal_entry SET amount_minor = 1"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM journal_entry"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_transaction SET description = 'edited' WHERE id = ?", tx))
                .hasMessageContaining("only change from POSTED to VOIDED");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_transaction WHERE id = ?", tx))
                .hasMessageContaining("cannot be deleted");
        assertThat(accounts.get(checking.getId()).balanceMinor()).isEqualTo(500);
    }

    @Test
    void writesAnAuditRowInTheSameTransactionAsEachChange() {
        Account equity = accounts.open("Equity", AccountType.EQUITY, "USD");
        Account checking = accounts.open("Checking", AccountType.ASSET, "USD");
        UUID tx = posting.post(new PostTransactionCommand("Opening balance", move(equity, checking, 500)))
                .transaction().getId();
        posting.reverse(tx, null);

        List<String> actions = jdbc.queryForList("SELECT action FROM audit_log ORDER BY created_at, id", String.class);
        assertThat(actions).containsExactly(
                "ACCOUNT_CREATED", "ACCOUNT_CREATED", "TRANSACTION_POSTED", "TRANSACTION_REVERSED");
    }

    @Test
    void publishesPostedAndReversedEventsWithEnoughDetailForConsumers() {
        Account checking = accounts.open("Checking - Alex", AccountType.ASSET, "USD");
        Account revenue = accounts.open("Revenue - Paycheck", AccountType.REVENUE, "USD");
        PostingResult paycheck = posting.post(new PostTransactionCommand(
                "Paycheck", move(revenue, checking, 240_000), null, new Origin(OriginType.RECURRING_TRANSFER, "sched-1")));
        PostingResult reversal = posting.reverse(paycheck.transaction().getId(), "Sent twice");
        try (KafkaTopicProbe probe = KafkaTopicProbe.subscribe(broker, Topics.TRANSACTIONS)) {
            ConsumerRecord<String, String> postedRecord =
                    probe.await(r -> r.key().equals(paycheck.transaction().getId().toString()), Duration.ofSeconds(15));
            ConsumerRecord<String, String> reversedRecord =
                    probe.await(r -> r.key().equals(reversal.transaction().getId().toString()), Duration.ofSeconds(15));

            IntegrationEvent posted = codec.read(postedRecord.value());
            assertThat(posted).isInstanceOfSatisfying(TransactionPosted.class, event -> {
                assertThat(event.transaction().originatedFrom("RECURRING_TRANSFER")).isTrue();
                assertThat(event.transaction().entries())
                        .extracting(e -> e.accountName() + "/" + e.accountType() + "/" + e.direction() + "/" + e.amountMinor())
                        .containsExactly("Checking - Alex/ASSET/DEBIT/240000", "Revenue - Paycheck/REVENUE/CREDIT/240000");
            });
            assertThat(codec.read(reversedRecord.value())).isInstanceOfSatisfying(TransactionReversed.class, event ->
                    assertThat(event.reversedTransactionId()).isEqualTo(paycheck.transaction().getId()));
        }
    }

    @Test
    void carriesTheRequestsTraceThroughTheOutboxToKafka() throws Exception {
        Account equity = accounts.open("Equity", AccountType.EQUITY, "USD");
        Account checking = accounts.open("Checking", AccountType.ASSET, "USD");
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

        String response = mvc.perform(post("/api/v1/transactions")
                        .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Traced","entries":[
                                  {"accountId":"%s","direction":"DEBIT","amountMinor":"100"},
                                  {"accountId":"%s","direction":"CREDIT","amountMinor":"100"}]}
                                """.formatted(checking.getId(), equity.getId())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String transactionId = response.replaceAll("(?s).*?\"id\":\"([^\"]+)\".*", "$1");

        try (KafkaTopicProbe probe = KafkaTopicProbe.subscribe(broker, Topics.TRANSACTIONS)) {
            ConsumerRecord<String, String> record = probe.await(r -> r.key().equals(transactionId), Duration.ofSeconds(15));
            var header = record.headers().lastHeader("traceparent");
            assertThat(header).as("traceparent header on the Kafka record").isNotNull();
            // Same trace, new span: the relay published it on another thread, later.
            assertThat(new String(header.value(), java.nio.charset.StandardCharsets.UTF_8)).contains(traceId);
        }
    }

    private static <T> List<T> runAllAtOnce(List<Callable<T>> tasks) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    results.add(future.get());
                } catch (ExecutionException e) {
                    throw new AssertionError("A concurrent call failed unexpectedly", e.getCause());
                }
            }
            return results;
        }
    }
}
