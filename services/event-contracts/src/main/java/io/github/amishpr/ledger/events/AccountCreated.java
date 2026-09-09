package io.github.amishpr.ledger.events;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

/** Published by the ledger service whenever an account is opened. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountCreated(UUID eventId, Instant occurredAt, int schemaVersion, AccountSnapshot account)
        implements IntegrationEvent {

    public static final String TYPE = "account.created";

    public static AccountCreated of(AccountSnapshot account, Instant occurredAt) {
        return new AccountCreated(UUID.randomUUID(), occurredAt, 1, account);
    }

    @Override
    @JsonIgnore
    public String eventType() {
        return TYPE;
    }

    @Override
    @JsonIgnore
    public String aggregateId() {
        return account.id().toString();
    }
}
