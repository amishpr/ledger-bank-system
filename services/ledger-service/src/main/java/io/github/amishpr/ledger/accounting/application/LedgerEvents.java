package io.github.amishpr.ledger.accounting.application;

import io.github.amishpr.ledger.accounting.domain.Account;
import io.github.amishpr.ledger.accounting.domain.JournalEntry;
import io.github.amishpr.ledger.accounting.domain.LedgerTransaction;
import io.github.amishpr.ledger.events.AccountCreated;
import io.github.amishpr.ledger.events.AccountSnapshot;
import io.github.amishpr.ledger.events.EntrySnapshot;
import io.github.amishpr.ledger.events.Origin;
import io.github.amishpr.ledger.events.Topics;
import io.github.amishpr.ledger.events.TransactionPosted;
import io.github.amishpr.ledger.events.TransactionReversed;
import io.github.amishpr.ledger.events.TransactionSnapshot;
import io.github.amishpr.ledger.platform.outbox.Outbox;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Translates ledger changes into the published event contracts and hands
 * them to the outbox, inside the same transaction as the change itself.
 * Internal entities never leave the service; only these snapshots do.
 */
@Component
class LedgerEvents {

    private final Outbox outbox;
    private final Clock clock;

    LedgerEvents(Outbox outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    void accountCreated(Account account) {
        outbox.append(Topics.ACCOUNTS, AccountCreated.of(snapshot(account), clock.instant()));
    }

    void transactionPosted(Journal.Written written) {
        LedgerTransaction tx = written.transaction();
        outbox.append(
                Topics.TRANSACTIONS,
                TransactionPosted.of(snapshot(tx, written.accounts()), tx.affectedAccountIds(), clock.instant()));
    }

    void transactionReversed(Journal.Written written, UUID reversedTransactionId) {
        LedgerTransaction tx = written.transaction();
        outbox.append(
                Topics.TRANSACTIONS,
                TransactionReversed.of(
                        snapshot(tx, written.accounts()), reversedTransactionId, tx.affectedAccountIds(), clock.instant()));
    }

    static AccountSnapshot snapshot(Account account) {
        return new AccountSnapshot(
                account.getId(), account.getName(), account.getType().name(), account.getCurrency(), account.getCreatedAt());
    }

    private static TransactionSnapshot snapshot(LedgerTransaction tx, Map<UUID, Account> accounts) {
        var origin = tx.getOrigin();
        return new TransactionSnapshot(
                tx.getId(),
                tx.getDescription(),
                tx.getStatus().name(),
                tx.getIdempotencyKey(),
                tx.getCreatedAt(),
                origin == null ? null : new Origin(origin.type().name(), origin.reference()),
                tx.getEntries().stream().map(entry -> snapshot(entry, accounts.get(entry.getAccountId()))).toList());
    }

    private static EntrySnapshot snapshot(JournalEntry entry, Account account) {
        return new EntrySnapshot(
                entry.getId(),
                entry.getTransactionId(),
                entry.getAccountId(),
                account.getName(),
                account.getType().name(),
                entry.getDirection().name(),
                entry.getAmountMinor(),
                entry.getCreatedAt());
    }
}
