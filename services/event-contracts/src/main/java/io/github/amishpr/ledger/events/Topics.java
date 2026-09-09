package io.github.amishpr.ledger.events;

/**
 * Kafka topic names. The version suffix is part of the contract: a change that
 * would break an existing consumer goes to a new topic instead of changing the
 * shape of an old one.
 */
public final class Topics {

    /** {@link AccountCreated}, keyed by account id. */
    public static final String ACCOUNTS = "ledger.accounts.v1";

    /** {@link TransactionPosted} and {@link TransactionReversed}, keyed by transaction id. */
    public static final String TRANSACTIONS = "ledger.transactions.v1";

    /** {@link RecurringTransferExecuted} and {@link RecurringTransferFailed}, keyed by schedule id. */
    public static final String RECURRING_TRANSFERS = "recurring.transfers.v1";

    /** Header carrying the event type, so tooling can route without parsing the body. */
    public static final String HEADER_EVENT_TYPE = "eventType";

    /** Header carrying the event id, the key every consumer deduplicates on. */
    public static final String HEADER_EVENT_ID = "eventId";

    private Topics() {}
}
