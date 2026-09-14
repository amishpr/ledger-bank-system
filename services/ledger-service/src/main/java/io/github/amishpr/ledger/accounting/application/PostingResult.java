package io.github.amishpr.ledger.accounting.application;

import io.github.amishpr.ledger.accounting.domain.LedgerTransaction;
import java.util.List;
import java.util.UUID;

/**
 * The outcome of a posting. {@code replayed} is true when an idempotency key
 * matched an earlier request and the original transaction was returned
 * instead of posting a second time.
 */
public record PostingResult(LedgerTransaction transaction, boolean replayed, List<UUID> affectedAccountIds) {

    static PostingResult created(LedgerTransaction transaction) {
        return new PostingResult(transaction, false, transaction.affectedAccountIds());
    }

    static PostingResult replayed(LedgerTransaction transaction) {
        return new PostingResult(transaction, true, transaction.affectedAccountIds());
    }
}
