package io.github.amishpr.ledger.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A posted transaction and all of its entries. {@code status} is POSTED or
 * VOIDED. {@code origin} is null for a transaction posted directly through
 * the API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransactionSnapshot(
        UUID id,
        String description,
        String status,
        String idempotencyKey,
        Instant createdAt,
        Origin origin,
        List<EntrySnapshot> entries) {

    public TransactionSnapshot {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public boolean originatedFrom(String originType) {
        return origin != null && originType.equals(origin.type());
    }
}
