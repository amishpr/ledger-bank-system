package io.github.amishpr.ledger.events;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Published by the ledger service when a new transaction is posted. Replays are not republished. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransactionPosted(
        UUID eventId,
        Instant occurredAt,
        int schemaVersion,
        TransactionSnapshot transaction,
        List<UUID> affectedAccountIds)
        implements IntegrationEvent {

    public static final String TYPE = "transaction.posted";

    public TransactionPosted {
        affectedAccountIds = affectedAccountIds == null ? List.of() : List.copyOf(affectedAccountIds);
    }

    public static TransactionPosted of(TransactionSnapshot transaction, List<UUID> affectedAccountIds, Instant occurredAt) {
        return new TransactionPosted(UUID.randomUUID(), occurredAt, 1, transaction, affectedAccountIds);
    }

    @Override
    @JsonIgnore
    public String eventType() {
        return TYPE;
    }

    @Override
    @JsonIgnore
    public String aggregateId() {
        return transaction.id().toString();
    }
}
