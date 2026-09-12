package io.github.amishpr.ledger.accounting.domain;

/**
 * The five account types of double entry bookkeeping. The type decides which
 * direction increases a balance: debits increase ASSET and EXPENSE accounts,
 * credits increase the other three.
 */
public enum AccountType {
    ASSET(true, true),
    LIABILITY(false, false),
    EQUITY(false, false),
    REVENUE(false, false),
    EXPENSE(true, false);

    private final boolean debitNormal;
    private final boolean overdraftProtected;

    AccountType(boolean debitNormal, boolean overdraftProtected) {
        this.debitNormal = debitNormal;
        this.overdraftProtected = overdraftProtected;
    }

    /** True when a debit increases this account's balance. */
    public boolean isDebitNormal() {
        return debitNormal;
    }

    /**
     * True for spendable, checking-account-like balances that may never go
     * negative. "Expenses so far" or "revenue so far" can, so those are not.
     */
    public boolean isOverdraftProtected() {
        return overdraftProtected;
    }

    /** How much an entry changes a balance of this type, signed. */
    public long signedDelta(EntryDirection direction, long amountMinor) {
        boolean increases = (direction == EntryDirection.DEBIT) == debitNormal;
        return increases ? amountMinor : -amountMinor;
    }

    /**
     * Converts a raw "debits minus credits" total into this type's balance.
     * The database sums every account the same way; the sign is fixed here.
     */
    public long balanceFromNetDebits(long netDebits) {
        return debitNormal ? netDebits : -netDebits;
    }
}
