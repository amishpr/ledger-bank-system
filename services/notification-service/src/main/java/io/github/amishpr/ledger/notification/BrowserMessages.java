package io.github.amishpr.ledger.notification;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.github.amishpr.ledger.events.AccountCreated;
import io.github.amishpr.ledger.events.EntrySnapshot;
import io.github.amishpr.ledger.events.IntegrationEvent;
import io.github.amishpr.ledger.events.Origin;
import io.github.amishpr.ledger.events.RecurringTransferExecuted;
import io.github.amishpr.ledger.events.RecurringTransferFailed;
import io.github.amishpr.ledger.events.TransactionPosted;
import io.github.amishpr.ledger.events.TransactionReversed;
import io.github.amishpr.ledger.events.TransactionSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The messages the dashboard understands (see {@code LedgerEvent} in
 * web/src/api/types.ts), built from internal events. Keeping this mapping
 * explicit means the event contracts between services can grow without the
 * browser ever noticing.
 */
final class BrowserMessages {

    private BrowserMessages() {}

    record Entry(
            UUID id,
            UUID transactionId,
            UUID accountId,
            String direction,
            @JsonFormat(shape = JsonFormat.Shape.STRING) long amountMinor,
            Instant createdAt) {}

    record Transaction(
            UUID id, String description, String status, String idempotencyKey, Instant createdAt, List<Entry> entries) {}

    record Connected(String type) {}

    record TransactionChanged(String type, Transaction transaction, List<UUID> affectedAccountIds) {}

    record RecurringExecuted(
            String type, UUID recurringTransferId, Transaction transaction, List<UUID> affectedAccountIds) {}

    record RecurringFailed(String type, UUID recurringTransferId, String error) {}

    static final Connected CONNECTED = new Connected("connected");

    /**
     * The message for an event, or empty when the dashboard should not hear
     * about it. A transaction posted by the scheduler is announced once, as
     * {@code recurring.executed}, not a second time as {@code transaction.posted}.
     */
    static Optional<Object> forEvent(IntegrationEvent event) {
        return switch (event) {
            case TransactionPosted posted when posted.transaction().originatedFrom(Origin.RECURRING_TRANSFER) ->
                    Optional.empty();
            case TransactionPosted posted -> Optional.of(new TransactionChanged(
                    "transaction.posted", transaction(posted.transaction()), posted.affectedAccountIds()));
            case TransactionReversed reversed -> Optional.of(new TransactionChanged(
                    "transaction.reversed", transaction(reversed.transaction()), reversed.affectedAccountIds()));
            case RecurringTransferExecuted executed -> Optional.of(new RecurringExecuted(
                    "recurring.executed",
                    executed.recurringTransferId(),
                    transaction(executed.transaction()),
                    executed.affectedAccountIds()));
            case RecurringTransferFailed failed ->
                    Optional.of(new RecurringFailed("recurring.failed", failed.recurringTransferId(), failed.error()));
            case AccountCreated ignored -> Optional.empty();
        };
    }

    private static Transaction transaction(TransactionSnapshot tx) {
        return new Transaction(
                tx.id(),
                tx.description(),
                tx.status(),
                tx.idempotencyKey(),
                tx.createdAt(),
                tx.entries().stream().map(BrowserMessages::entry).toList());
    }

    private static Entry entry(EntrySnapshot e) {
        return new Entry(e.id(), e.transactionId(), e.accountId(), e.direction(), e.amountMinor(), e.createdAt());
    }
}
