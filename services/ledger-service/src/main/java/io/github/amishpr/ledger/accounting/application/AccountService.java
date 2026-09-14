package io.github.amishpr.ledger.accounting.application;

import io.github.amishpr.ledger.accounting.domain.Account;
import io.github.amishpr.ledger.accounting.domain.AccountType;
import io.github.amishpr.ledger.accounting.domain.LedgerException;
import io.github.amishpr.ledger.accounting.repository.AccountRepository;
import io.github.amishpr.ledger.accounting.repository.AuditLog;
import io.github.amishpr.ledger.accounting.repository.BalanceQueries;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AccountService {

    public record AccountWithBalance(Account account, long balanceMinor) {}

    private final AccountRepository accounts;
    private final BalanceQueries balances;
    private final AuditLog auditLog;
    private final LedgerEvents events;
    private final Clock clock;

    AccountService(
            AccountRepository accounts, BalanceQueries balances, AuditLog auditLog, LedgerEvents events, Clock clock) {
        this.accounts = accounts;
        this.balances = balances;
        this.auditLog = auditLog;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Account open(String name, AccountType type, String currency) {
        Account account = Account.open(name, type, currency == null ? "USD" : currency, clock.instant());
        accounts.saveAndFlush(account);
        auditLog.record(
                "Account",
                account.getId(),
                "ACCOUNT_CREATED",
                null,
                Map.of("name", account.getName(), "type", account.getType()),
                account.getCreatedAt());
        events.accountCreated(account);
        return account;
    }

    public List<AccountWithBalance> list() {
        Map<UUID, Long> netDebits = balances.netDebitsByAccount();
        return accounts.findAllByOrderByCreatedAtAscIdAsc().stream()
                .map(a -> new AccountWithBalance(a, a.getType().balanceFromNetDebits(netDebits.getOrDefault(a.getId(), 0L))))
                .toList();
    }

    public AccountWithBalance get(UUID id) {
        Account account = require(id);
        return new AccountWithBalance(account, account.getType().balanceFromNetDebits(balances.netDebits(id)));
    }

    public Account require(UUID id) {
        return accounts.findById(id).orElseThrow(() -> LedgerException.accountNotFound(id));
    }

    public boolean isEmpty() {
        return accounts.count() == 0;
    }
}
