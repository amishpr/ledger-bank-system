package io.github.amishpr.ledger.accounting.repository;

import io.github.amishpr.ledger.accounting.domain.EntryDirection;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Balance and statement queries in plain SQL. Every account is summed the
 * same way here, as debits minus credits, and the account type turns that
 * into a balance afterwards (see {@code AccountType#balanceFromNetDebits}).
 *
 * <p>These answer from the {@code journal_entry_account_idx} covering index.
 * At bank scale the next step would be a balance snapshot per account updated
 * in the posting transaction, with these sums kept as the reconciliation check.
 */
@Repository
public class BalanceQueries {

    private static final String NET = "CASE WHEN direction = 'DEBIT' THEN amount_minor ELSE -amount_minor END";

    private final JdbcClient jdbc;

    public BalanceQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long netDebits(UUID accountId) {
        return jdbc.sql("SELECT COALESCE(SUM(" + NET + "), 0) FROM journal_entry WHERE account_id = :id")
                .param("id", accountId)
                .query(Long.class)
                .single();
    }

    /** Net debits for every account that has entries, in one query rather than one per account. */
    public Map<UUID, Long> netDebitsByAccount() {
        Map<UUID, Long> totals = new HashMap<>();
        jdbc.sql("SELECT account_id, SUM(" + NET + ") AS net FROM journal_entry GROUP BY account_id")
                .query(rs -> {
                    totals.put(rs.getObject("account_id", UUID.class), rs.getLong("net"));
                });
        return totals;
    }

    /**
     * The newest {@code limit} entries for an account, each with the running
     * net total up to and including it. The window function runs over the
     * account's whole history before the limit is applied, so the running
     * figure on the oldest returned line is still correct.
     */
    public List<StatementRow> statement(UUID accountId, int limit) {
        return jdbc.sql("""
                SELECT e.id, e.transaction_id, t.description, e.direction, e.amount_minor, e.created_at,
                       SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END)
                           OVER (ORDER BY e.created_at, e.seq ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)
                           AS running_net
                FROM journal_entry e
                JOIN ledger_transaction t ON t.id = e.transaction_id
                WHERE e.account_id = :accountId
                ORDER BY e.created_at DESC, e.seq DESC
                LIMIT :limit
                """)
                .param("accountId", accountId)
                .param("limit", limit)
                .query((rs, row) -> new StatementRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("transaction_id", UUID.class),
                        rs.getString("description"),
                        EntryDirection.valueOf(rs.getString("direction")),
                        rs.getLong("amount_minor"),
                        rs.getLong("running_net"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public record StatementRow(
            UUID entryId,
            UUID transactionId,
            String description,
            EntryDirection direction,
            long amountMinor,
            long runningNetDebits,
            Instant createdAt) {}
}
