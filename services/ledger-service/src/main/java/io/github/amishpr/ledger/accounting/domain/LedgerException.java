package io.github.amishpr.ledger.accounting.domain;

import io.github.amishpr.ledger.platform.web.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Every way a ledger operation can be refused. The codes match the original
 * Node API one for one, so clients written against it keep working.
 */
public final class LedgerException extends ApiException {

    private LedgerException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static LedgerException tooFewEntries() {
        return new LedgerException(HttpStatus.UNPROCESSABLE_CONTENT, "TOO_FEW_ENTRIES", "A transaction needs at least two entries");
    }

    public static LedgerException invalidAmount() {
        return new LedgerException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_AMOUNT", "Entry amounts must be positive");
    }

    public static LedgerException unbalanced(long debits, long credits) {
        return new LedgerException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "UNBALANCED_TRANSACTION",
                "Transaction does not balance: debits=" + debits + " credits=" + credits);
    }

    public static LedgerException accountNotFound(UUID accountId) {
        return new LedgerException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account " + accountId + " not found");
    }

    public static LedgerException transactionNotFound(UUID transactionId) {
        return new LedgerException(
                HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", "Transaction " + transactionId + " not found");
    }

    public static LedgerException insufficientFunds(UUID accountId) {
        return new LedgerException(
                HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS", "Account " + accountId + " has insufficient funds");
    }

    public static LedgerException currencyMismatch() {
        return new LedgerException(
                HttpStatus.UNPROCESSABLE_CONTENT, "CURRENCY_MISMATCH", "All entries in a transaction must share one currency");
    }

    public static LedgerException idempotencyConflict(String key) {
        return new LedgerException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_CONFLICT",
                "Idempotency key " + key + " was already used with a different request body");
    }

    public static LedgerException alreadyVoided(UUID transactionId) {
        return new LedgerException(
                HttpStatus.CONFLICT, "ALREADY_VOIDED", "Transaction " + transactionId + " is already voided");
    }
}
