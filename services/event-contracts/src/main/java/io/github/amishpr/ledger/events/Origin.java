package io.github.amishpr.ledger.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Where a transaction came from when it was not typed in by a person, for
 * example a recurring transfer and that schedule's id.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Origin(String type, String reference) {

    public static final String RECURRING_TRANSFER = "RECURRING_TRANSFER";
}
