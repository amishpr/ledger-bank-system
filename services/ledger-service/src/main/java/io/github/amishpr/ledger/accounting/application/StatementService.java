package io.github.amishpr.ledger.accounting.application;

import io.github.amishpr.ledger.accounting.domain.Account;
import io.github.amishpr.ledger.accounting.domain.EntryDirection;
import io.github.amishpr.ledger.accounting.repository.BalanceQueries;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class StatementService {

    /** One statement line, newest first, with the account's balance right after this entry. */
    public record StatementLine(
            UUID entryId,
            UUID transactionId,
            String description,
            EntryDirection direction,
            long amountMinor,
            long runningBalanceMinor,
            Instant createdAt) {}

    public record Statement(Account account, List<StatementLine> lines) {}

    private final AccountService accounts;
    private final BalanceQueries balances;

    StatementService(AccountService accounts, BalanceQueries balances) {
        this.accounts = accounts;
        this.balances = balances;
    }

    public Statement statement(UUID accountId, int limit) {
        Account account = accounts.require(accountId);
        List<StatementLine> lines = balances.statement(accountId, limit).stream()
                .map(row -> new StatementLine(
                        row.entryId(),
                        row.transactionId(),
                        row.description(),
                        row.direction(),
                        row.amountMinor(),
                        account.getType().balanceFromNetDebits(row.runningNetDebits()),
                        row.createdAt()))
                .toList();
        return new Statement(account, lines);
    }
}
