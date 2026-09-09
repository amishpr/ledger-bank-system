package io.github.amishpr.ledger.events;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by the recurring transfer service when the ledger rejects a
 * scheduled occurrence, for example for insufficient funds. {@code errorCode}
 * is the ledger's stable error code and {@code error} its readable message.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecurringTransferFailed(
        UUID eventId,
        Instant occurredAt,
        int schemaVersion,
        UUID recurringTransferId,
        String errorCode,
        String error)
        implements IntegrationEvent {

    public static final String TYPE = "recurring-transfer.failed";

    public static RecurringTransferFailed of(
            UUID recurringTransferId, String errorCode, String error, Instant occurredAt) {
        return new RecurringTransferFailed(UUID.randomUUID(), occurredAt, 1, recurringTransferId, errorCode, error);
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
