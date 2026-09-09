package io.github.amishpr.ledger.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class IntegrationEventJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final UUID transactionId = UUID.randomUUID();
    private final UUID checking = UUID.randomUUID();
    private final UUID groceries = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-01T12:00:00.123Z");

    private TransactionSnapshot groceriesRun() {
        return new TransactionSnapshot(
                transactionId,
                "Groceries",
                "POSTED",
                "key-1",
                now,
                null,
                List.of(
                        new EntrySnapshot(UUID.randomUUID(), transactionId, groceries, "Expenses - Groceries", "EXPENSE", "DEBIT", 1050, now),
                        new EntrySnapshot(UUID.randomUUID(), transactionId, checking, "Checking - Alex", "ASSET", "CREDIT", 1050, now)));
    }

    @Test
    void writesTheDiscriminatorAndCentsAsStrings() {
        String json = mapper.writeValueAsString(TransactionPosted.of(groceriesRun(), List.of(groceries, checking), now));

        assertThat(json).contains("\"eventType\":\"transaction.posted\"");
        assertThat(json).contains("\"amountMinor\":\"1050\"");
        assertThat(json).contains("\"occurredAt\":\"2026-10-01T12:00:00.123Z\"");
        assertThat(json).doesNotContain("aggregateId");
    }

    @Test
    void readsBackAsTheRightSubtype() {
        TransactionPosted original = TransactionPosted.of(groceriesRun(), List.of(groceries, checking), now);

        IntegrationEvent read = mapper.readValue(mapper.writeValueAsString(original), IntegrationEvent.class);

        assertThat(read).isEqualTo(original);
        assertThat(read.aggregateId()).isEqualTo(transactionId.toString());
    }

    @Test
    void ignoresFieldsAddedByANewerProducer() {
        String json = """
                {"eventType":"recurring-transfer.failed","eventId":"%s","occurredAt":"2026-10-01T12:00:00Z",
                 "schemaVersion":1,"recurringTransferId":"%s","errorCode":"INSUFFICIENT_FUNDS",
                 "error":"Account has insufficient funds","retryHint":"next occurrence"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        IntegrationEvent read = mapper.readValue(json, IntegrationEvent.class);

        assertThat(read).isInstanceOfSatisfying(RecurringTransferFailed.class, failed -> {
            assertThat(failed.errorCode()).isEqualTo("INSUFFICIENT_FUNDS");
        });
    }

    @Test
    void rejectsAnUnknownEventType() {
        String json = "{\"eventType\":\"account.renamed\",\"eventId\":\"" + UUID.randomUUID() + "\"}";

        assertThatThrownBy(() -> mapper.readValue(json, IntegrationEvent.class)).isInstanceOf(JacksonException.class);
    }

    @Test
    void recognisesWhereATransactionCameFrom() {
        TransactionSnapshot scheduled = new TransactionSnapshot(
                transactionId, "Savings sweep", "POSTED", null, now, new Origin(Origin.RECURRING_TRANSFER, "abc"), List.of());

        assertThat(scheduled.originatedFrom(Origin.RECURRING_TRANSFER)).isTrue();
        assertThat(groceriesRun().originatedFrom(Origin.RECURRING_TRANSFER)).isFalse();
    }
}
