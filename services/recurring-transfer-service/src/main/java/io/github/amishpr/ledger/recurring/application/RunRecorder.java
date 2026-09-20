package io.github.amishpr.ledger.recurring.application;

import io.github.amishpr.ledger.events.EntrySnapshot;
import io.github.amishpr.ledger.events.Origin;
import io.github.amishpr.ledger.events.RecurringTransferExecuted;
import io.github.amishpr.ledger.events.RecurringTransferFailed;
import io.github.amishpr.ledger.events.Topics;
import io.github.amishpr.ledger.events.TransactionSnapshot;
import io.github.amishpr.ledger.platform.outbox.Outbox;
import io.github.amishpr.ledger.recurring.client.LedgerClient;
import io.github.amishpr.ledger.recurring.client.LedgerGateway.PostingOutcome;
import io.github.amishpr.ledger.recurring.domain.AccountReplica;
import io.github.amishpr.ledger.recurring.domain.RecurringTransfer;
import io.github.amishpr.ledger.recurring.repository.RecurringTransferRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the outcome of one occurrence, in its own transaction, together
 * with the event that announces it. The ledger call itself happens before
 * this, outside any database transaction, so no connection or row lock is
 * held while waiting on the network.
 */
@Component
class RunRecorder {

    private final RecurringTransferRepository schedules;
    private final AccountDirectory accounts;
    private final Outbox outbox;
    private final Clock clock;
    private final ZoneId zone;

    RunRecorder(
            RecurringTransferRepository schedules,
            AccountDirectory accounts,
            Outbox outbox,
            Clock clock,
            @Value("${recurring.zone:#{T(java.time.ZoneId).systemDefault().id}}") String zone) {
        this.schedules = schedules;
        this.accounts = accounts;
        this.outbox = outbox;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    /**
     * Returns false when the schedule changed while the ledger was being
     * called (deleted, paused, or already advanced). The posting itself is
     * safe either way: if the occurrence comes due again, the same key
     * replays it rather than posting twice.
     */
    @Transactional
    boolean record(UUID scheduleId, Instant dueAt, PostingOutcome outcome) {
        Optional<RecurringTransfer> current = schedules.findById(scheduleId);
        if (current.isEmpty() || !current.get().isActive() || !current.get().getNextRunAt().equals(dueAt)) {
            return false;
        }
        RecurringTransfer transfer = current.get();
        Instant now = clock.instant();
        switch (outcome) {
            case PostingOutcome.Posted posted -> {
                transfer.recordSuccess(now, zone);
                outbox.append(Topics.RECURRING_TRANSFERS, RecurringTransferExecuted.of(
                        transfer.getId(), snapshot(transfer, posted.result()), posted.result().affectedAccountIds(), now));
            }
            case PostingOutcome.Rejected rejected -> {
                transfer.recordRejection(now, rejected.message(), zone);
                outbox.append(Topics.RECURRING_TRANSFERS,
                        RecurringTransferFailed.of(transfer.getId(), rejected.code(), rejected.message(), now));
            }
            // No event: the occurrence is still due and the dashboard would
            // only get a stream of identical failures every fifteen seconds.
            case PostingOutcome.Unavailable unavailable -> transfer.recordUnavailable(now, unavailable.reason());
        }
        return true;
    }

    private TransactionSnapshot snapshot(RecurringTransfer transfer, LedgerClient.PostResult result) {
        LedgerClient.Transaction tx = result.transaction();
        Map<UUID, AccountReplica> names = accounts.findAll(java.util.List.of(transfer.getFromAccountId(), transfer.getToAccountId()));
        return new TransactionSnapshot(
                tx.id(),
                tx.description(),
                tx.status(),
                tx.idempotencyKey(),
                tx.createdAt(),
                new Origin(Origin.RECURRING_TRANSFER, transfer.getId().toString()),
                tx.entries().stream().map(e -> {
                    AccountReplica account = names.get(e.accountId());
                    return new EntrySnapshot(
                            e.id(),
                            e.transactionId(),
                            e.accountId(),
                            account == null ? null : account.getName(),
                            account == null ? null : account.getType(),
                            e.direction(),
                            Long.parseLong(e.amountMinor()),
                            e.createdAt());
                }).toList());
    }
}
