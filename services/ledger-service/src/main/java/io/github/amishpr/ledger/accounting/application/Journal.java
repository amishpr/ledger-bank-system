package io.github.amishpr.ledger.accounting.application;

import io.github.amishpr.ledger.accounting.domain.Account;
import io.github.amishpr.ledger.accounting.domain.LedgerException;
import io.github.amishpr.ledger.accounting.domain.LedgerTransaction;
import io.github.amishpr.ledger.accounting.domain.Origin;
import io.github.amishpr.ledger.accounting.domain.PostingLine;
import io.github.amishpr.ledger.accounting.repository.AccountRepository;
import io.github.amishpr.ledger.accounting.repository.AuditLog;
import io.github.amishpr.ledger.accounting.repository.BalanceQueries;
import io.github.amishpr.ledger.accounting.repository.LedgerTransactionRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one and only path through which entries are ever written. A normal
 * posting, a reversal and every scheduled transfer all come through
 * {@link #write}, so the rules that need the database only live here.
 */
@Component
class Journal {

    private final AccountRepository accounts;
    private final LedgerTransactionRepository transactions;
    private final BalanceQueries balances;
    private final AuditLog auditLog;

    Journal(
            AccountRepository accounts,
            LedgerTransactionRepository transactions,
            BalanceQueries balances,
            AuditLog auditLog) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.balances = balances;
        this.auditLog = auditLog;
    }

    /** A written transaction together with the accounts it touched, as they were locked. */
    record Written(LedgerTransaction transaction, Map<UUID, Account> accounts) {}

    /**
     * Writes a transaction that has already passed {@code PostingRules}. Runs
     * inside the caller's transaction and refuses to run without one.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Written write(
            String description,
            List<PostingLine> lines,
            String idempotencyKey,
            String requestHash,
            Origin origin,
            UUID reversalOf,
            Instant at) {
        List<UUID> accountIds = lines.stream().map(PostingLine::accountId).distinct().sorted().toList();

        // Lock first, then read balances, so no other posting can change these
        // accounts between the overdraft check and the insert.
        Map<UUID, Account> locked = accounts.lockAllById(accountIds).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        for (UUID id : accountIds) {
            if (!locked.containsKey(id)) {
                throw LedgerException.accountNotFound(id);
            }
        }
        if (locked.values().stream().map(Account::getCurrency).distinct().count() > 1) {
            throw LedgerException.currencyMismatch();
        }

        Map<UUID, Long> deltas = new HashMap<>();
        for (PostingLine line : lines) {
            Account account = locked.get(line.accountId());
            deltas.merge(line.accountId(), account.getType().signedDelta(line.direction(), line.amountMinor()), Math::addExact);
        }
        for (Account account : locked.values()) {
            if (!account.getType().isOverdraftProtected()) {
                continue;
            }
            long balance = account.getType().balanceFromNetDebits(balances.netDebits(account.getId()));
            if (balance + deltas.get(account.getId()) < 0) {
                throw LedgerException.insufficientFunds(account.getId());
            }
        }

        LedgerTransaction transaction =
                LedgerTransaction.post(description, lines, idempotencyKey, requestHash, origin, reversalOf, at);
        // Flushed now, rather than at commit, so the audit row below can
        // reference it and a duplicate idempotency key fails right here.
        transactions.saveAndFlush(transaction);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("description", description);
        if (reversalOf != null) {
            metadata.put("reversalOf", reversalOf);
        }
        if (origin != null) {
            metadata.put("origin", Map.of("type", origin.type(), "reference", origin.reference()));
        }
        auditLog.record(
                "Transaction",
                transaction.getId(),
                reversalOf == null ? "TRANSACTION_POSTED" : "TRANSACTION_REVERSED",
                transaction.getId(),
                metadata,
                at);

        return new Written(transaction, locked);
    }
}
