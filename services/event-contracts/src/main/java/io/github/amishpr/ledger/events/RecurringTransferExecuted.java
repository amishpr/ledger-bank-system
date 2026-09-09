package io.github.amishpr.ledger.events;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Published by the recurring transfer service after a scheduled occurrence posts. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecurringTransferExecuted(
        UUID eventId,
        Instant occurredAt,
        int schemaVersion,
        UUID recurringTransferId,
        TransactionSnapshot transaction,
        List<UUID> affectedAccountIds)
        implements IntegrationEvent {

    public static final String TYPE = "recurring-transfer.executed";

    public RecurringTransferExecuted {
        affectedAccountIds = affectedAccountIds == null ? List.of() : List.copyOf(affectedAccountIds);
    }

    public static RecurringTransferExecuted of(
            UUID recurringTransferId, TransactionSnapshot transaction, List<UUID> affectedAccountIds, Instant occurredAt) {
        return new RecurringTransferExecuted(
                UUID.randomUUID(), occurredAt, 1, recurringTransferId, transaction, affectedAccountIds);
    }

    @Override
    @JsonIgnore
    public String eventType() {
        return TYPE;
    }

    @Override
    @JsonIgnore
    public String aggregateId() {
        return recurringTransferId.toString();
    }
}
