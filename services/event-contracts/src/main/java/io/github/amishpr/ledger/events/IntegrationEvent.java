package io.github.amishpr.ledger.events;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.util.UUID;

/**
 * Every event any service publishes. The {@code eventType} property on the wire
 * picks the record type, and the sealed hierarchy lets a consumer switch over
 * every case and have the compiler tell it when a new one is added.
 *
 * <p>Compatibility rules for a published version: never remove or rename a
 * field, only add optional ones, and move to a new topic for anything else.
 * Consumers ignore fields they do not know about.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "eventType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AccountCreated.class, name = AccountCreated.TYPE),
    @JsonSubTypes.Type(value = TransactionPosted.class, name = TransactionPosted.TYPE),
    @JsonSubTypes.Type(value = TransactionReversed.class, name = TransactionReversed.TYPE),
    @JsonSubTypes.Type(value = RecurringTransferExecuted.class, name = RecurringTransferExecuted.TYPE),
    @JsonSubTypes.Type(value = RecurringTransferFailed.class, name = RecurringTransferFailed.TYPE),
})
public sealed interface IntegrationEvent
        permits AccountCreated,
                TransactionPosted,
                TransactionReversed,
                RecurringTransferExecuted,
                RecurringTransferFailed {

    /** Unique per event. Consumers use it to make handling idempotent. */
    UUID eventId();

    /** When the change happened in the producing service. */
    Instant occurredAt();

    /** Version of this event's shape within its topic. */
    int schemaVersion();

    /** The discriminator written to the {@code eventType} property. */
    String eventType();

    /** The id used as the Kafka record key, which decides partition and ordering. */
    String aggregateId();
}
