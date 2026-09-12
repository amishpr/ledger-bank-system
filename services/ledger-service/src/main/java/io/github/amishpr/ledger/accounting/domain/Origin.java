package io.github.amishpr.ledger.accounting.domain;

import java.util.Objects;

/** A transaction's origin, for example a recurring transfer and that schedule's id. */
public record Origin(OriginType type, String reference) {

    public Origin {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(reference, "reference");
    }
}
