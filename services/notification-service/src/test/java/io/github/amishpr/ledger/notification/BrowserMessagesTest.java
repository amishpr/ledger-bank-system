package io.github.amishpr.ledger.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amishpr.ledger.events.AccountCreated;
import io.github.amishpr.ledger.events.AccountSnapshot;
import io.github.amishpr.ledger.events.EntrySnapshot;
import io.github.amishpr.ledger.events.Origin;
import io.github.amishpr.ledger.events.RecurringTransferExecuted;
import io.github.amishpr.ledger.events.RecurringTransferFailed;
import io.github.amishpr.ledger.events.TransactionPosted;
import io.github.amishpr.ledger.events.TransactionSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class BrowserMessagesTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final UUID txId = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private final UUID checking = UUID.fromString("00000000-0000-7000-8000-0000000000aa");
    private final UUID savings = UUID.fromString("00000000-0000-7000-8000-0000000000bb");
    private final Instant at = Instant.parse("2026-10-01T12:00:00.250Z");

    private TransactionSnapshot sweep(Origin origin) {
        return new TransactionSnapshot(txId, "Automatic savings sweep", "POSTED", "recurring:x", at, origin, List.of(
                new EntrySnapshot(UUID.randomUUID(), txId, savings, "Savings - Alex", "ASSET", "DEBIT", 5_000, at),
                new EntrySnapshot(UUID.randomUUID(), txId, checking, "Checking - Alex", "ASSET", "CREDIT", 5_000, at)));
    }

    private JsonNode render(Object message) {
        return json.readTree(json.writeValueAsString(message));
    }

    @Test
    void shapesAPostedTransactionTheWayTheDashboardReadsIt() {
        Object message = BrowserMessages.forEvent(TransactionPosted.of(sweep(null), List.of(savings, checking), at)).orElseThrow();
        JsonNode node = render(message);

        assertThat(node.get("type").asString()).isEqualTo("transaction.posted");
        assertThat(node.get("affectedAccountIds").size()).isEqualTo(2);
        JsonNode tx = node.get("transaction");
        assertThat(tx.get("id").asString()).isEqualTo(txId.toString());
        assertThat(tx.get("status").asString()).isEqualTo("POSTED");
        assertThat(tx.get("createdAt").asString()).isEqualTo("2026-10-01T12:00:00.250Z");
        JsonNode entry = tx.get("entries").get(0);
        assertThat(entry.get("amountMinor").isString()).isTrue();
        assertThat(entry.get("amountMinor").asString()).isEqualTo("5000");
        // Internal details stay internal.
        assertThat(entry.has("accountName")).isFalse();
        assertThat(tx.has("origin")).isFalse();
    }

    @Test
    void announcesAScheduledTransferOnceNotTwice() {
        TransactionSnapshot scheduled = sweep(new Origin(Origin.RECURRING_TRANSFER, "sched-1"));

        assertThat(BrowserMessages.forEvent(TransactionPosted.of(scheduled, List.of(), at))).isEmpty();
        JsonNode executed = render(BrowserMessages.forEvent(
                RecurringTransferExecuted.of(UUID.randomUUID(), scheduled, List.of(savings, checking), at)).orElseThrow());
        assertThat(executed.get("type").asString()).isEqualTo("recurring.executed");
        assertThat(executed.get("transaction").get("entries").size()).isEqualTo(2);
    }

    @Test
    void passesAFailedScheduleThroughWithItsMessage() {
        UUID schedule = UUID.randomUUID();
        JsonNode failed = render(BrowserMessages.forEvent(
                RecurringTransferFailed.of(schedule, "INSUFFICIENT_FUNDS", "Account has insufficient funds", at)).orElseThrow());

        assertThat(failed.get("type").asString()).isEqualTo("recurring.failed");
        assertThat(failed.get("recurringTransferId").asString()).isEqualTo(schedule.toString());
        assertThat(failed.get("error").asString()).isEqualTo("Account has insufficient funds");
    }

    @Test
    void keepsAccountEventsOffTheSocket() {
        assertThat(BrowserMessages.forEvent(AccountCreated.of(
                new AccountSnapshot(checking, "Checking", "ASSET", "USD", at), at))).isEmpty();
    }
}
