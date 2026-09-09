package io.github.amishpr.ledger.events;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published by the ledger service when a transaction is reversed.
 * {@code transaction} is the new reversing transaction, and
 * {@code reversedTransactionId} is the original one, now voided.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransactionReversed(
        UUID eventId,
        Instant occurredAt,
        int schemaVersion,
        TransactionSnapshot transaction,
        UUID reversedTransactionId,
        List<UUID> affectedAccountIds)
        implements IntegrationEvent {

    public static final String TYPE = "transaction.reversed";

    public TransactionReversed {
        affectedAccountIds = affectedAccountIds == null ? List.of() : List.copyOf(affectedAccountIds);
    }

    public static TransactionReversed of(
            TransactionSnapshot transaction, UUID reversedTransactionId, List<UUID> affectedAccountIds, Instant occurredAt) {
        return new TransactionReversed(
                UUID.randomUUID(), occurredAt, 1, transaction, reversedTransactionId, affectedAccountIds);
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
