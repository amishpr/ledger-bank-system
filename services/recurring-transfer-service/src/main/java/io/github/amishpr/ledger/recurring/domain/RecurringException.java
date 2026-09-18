package io.github.amishpr.ledger.recurring.domain;

import io.github.amishpr.ledger.platform.web.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Errors this service returns. Codes match the original API's. */
public final class RecurringException extends ApiException {

    private RecurringException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static RecurringException notFound(UUID id) {
        return new RecurringException(
                HttpStatus.NOT_FOUND, "RECURRING_TRANSFER_NOT_FOUND", "Recurring transfer " + id + " not found");
    }

    public static RecurringException sameAccount() {
        return new RecurringException(
                HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_RECURRING_TRANSFER", "From and to accounts must be different");
    }

    public static RecurringException notAssetAccounts() {
        return new RecurringException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "INVALID_RECURRING_TRANSFER",
                "Recurring transfers only run between asset accounts, the same as a manual transfer");
    }

    public static RecurringException invalidAmount() {
        return new RecurringException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_AMOUNT", "Amount must be greater than zero");
    }

    public static RecurringException accountNotFound(UUID id) {
        return new RecurringException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account " + id + " not found");
    }

    public static RecurringException ledgerUnavailable() {
        return new RecurringException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "LEDGER_UNAVAILABLE",
                "The ledger service is not responding right now. Try again in a moment.");
    }
}
