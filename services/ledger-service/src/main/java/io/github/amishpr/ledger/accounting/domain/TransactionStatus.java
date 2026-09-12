package io.github.amishpr.ledger.accounting.domain;

/** A transaction is POSTED when written and VOIDED once it has been reversed. Nothing else. */
public enum TransactionStatus {
    POSTED,
    VOIDED
}
