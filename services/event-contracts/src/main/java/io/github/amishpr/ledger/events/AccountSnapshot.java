package io.github.amishpr.ledger.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

/**
 * An account as it was when an event was published. Account type is one of
 * ASSET, LIABILITY, EQUITY, REVENUE or EXPENSE, kept as a string so a consumer
 * built before a new type existed can still read the event.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountSnapshot(UUID id, String name, String type, String currency, Instant createdAt) {}
