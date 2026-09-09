package io.github.amishpr.ledger.events;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

/**
 * One leg of a transaction. It carries the account's name and type along with
 * its id so a consumer such as the insights service can build its read model
 * without ever calling back into the ledger.
 *
 * <p>{@code amountMinor} is always positive and travels as a string of whole
 * cents, the same as on the REST API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EntrySnapshot(
        UUID id,
        UUID transactionId,
        UUID accountId,
        String accountName,
        String accountType,
        String direction,
        @JsonFormat(shape = JsonFormat.Shape.STRING) long amountMinor,
        Instant createdAt) {

    public static final String DEBIT = "DEBIT";
    public static final String CREDIT = "CREDIT";
}
