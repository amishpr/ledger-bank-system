package io.github.amishpr.ledger.accounting.domain;

import java.util.Objects;
import java.util.UUID;

/** One requested leg of a transaction, before it is written. */
public record PostingLine(UUID accountId, EntryDirection direction, long amountMinor) {

    public PostingLine {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(direction, "direction");
    }

    public PostingLine flipped() {
        return new PostingLine(accountId, direction.opposite(), amountMinor);
    }
}
