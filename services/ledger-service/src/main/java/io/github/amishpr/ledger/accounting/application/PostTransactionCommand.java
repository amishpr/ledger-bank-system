package io.github.amishpr.ledger.accounting.application;

import io.github.amishpr.ledger.accounting.domain.Origin;
import io.github.amishpr.ledger.accounting.domain.PostingLine;
import java.util.List;
import java.util.Objects;

/** A request to post a transaction. {@code idempotencyKey} and {@code origin} are optional. */
public record PostTransactionCommand(String description, List<PostingLine> lines, String idempotencyKey, Origin origin) {

    public PostTransactionCommand {
        Objects.requireNonNull(description, "description");
        lines = List.copyOf(lines);
    }

    public PostTransactionCommand(String description, List<PostingLine> lines) {
        this(description, lines, null, null);
    }
}
