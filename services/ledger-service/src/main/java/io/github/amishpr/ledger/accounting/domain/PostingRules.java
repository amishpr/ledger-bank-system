package io.github.amishpr.ledger.accounting.domain;

import java.util.List;

/**
 * The rules a transaction must satisfy before anything touches the database:
 * at least two entries, every amount positive, and debits equal to credits.
 * The rules that need the database (accounts exist, one currency, no
 * overdraft) are checked under row locks by the journal.
 */
public final class PostingRules {

    private PostingRules() {}

    public static void validate(List<PostingLine> lines) {
        if (lines == null || lines.size() < 2) {
            throw LedgerException.tooFewEntries();
        }
        long debits = 0;
        long credits = 0;
        for (PostingLine line : lines) {
            if (line.amountMinor() <= 0) {
                throw LedgerException.invalidAmount();
            }
            // addExact: a total that overflows a long is rejected, never wrapped.
            if (line.direction() == EntryDirection.DEBIT) {
                debits = Math.addExact(debits, line.amountMinor());
            } else {
                credits = Math.addExact(credits, line.amountMinor());
            }
        }
        if (debits != credits) {
            throw LedgerException.unbalanced(debits, credits);
        }
    }
}
