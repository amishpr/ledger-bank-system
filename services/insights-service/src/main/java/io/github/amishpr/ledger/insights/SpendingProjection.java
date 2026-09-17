package io.github.amishpr.ledger.insights;

import io.github.amishpr.ledger.events.EntrySnapshot;
import io.github.amishpr.ledger.events.TransactionSnapshot;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies ledger transactions to the spending read model.
 *
 * <p>"Spending" means what the original API meant by it: a debit entry against
 * an EXPENSE account, bucketed by the transaction's month in UTC. The event
 * carries each entry's account name and type, so nothing here has to ask the
 * ledger anything.
 */
@Component
class SpendingProjection {

    static final String EXPENSE = "EXPENSE";

    private final JdbcClient jdbc;
    private final Clock clock;

    SpendingProjection(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Applies one event exactly once. Returns false when the event id has been
     * seen before, which happens normally with at-least-once delivery.
     */
    @Transactional
    boolean apply(UUID eventId, String eventType, TransactionSnapshot transaction) {
        Instant now = clock.instant();
        int claimed = jdbc.sql("""
                INSERT INTO processed_event (event_id, event_type, processed_at)
                VALUES (:id, :type, :now)
                ON CONFLICT (event_id) DO NOTHING
                """)
                .param("id", eventId)
                .param("type", eventType)
                .param("now", Timestamp.from(now))
                .update();
        if (claimed == 0) {
            return false;
        }

        String month = YearMonth.from(transaction.createdAt().atZone(ZoneOffset.UTC)).toString();
        for (EntrySnapshot entry : transaction.entries()) {
            if (!EXPENSE.equals(entry.accountType()) || !EntrySnapshot.DEBIT.equals(entry.direction())) {
                continue;
            }
            jdbc.sql("""
                    INSERT INTO spending_by_category (account_id, account_name, total_minor, updated_at)
                    VALUES (:accountId, :name, :amount, :now)
                    ON CONFLICT (account_id) DO UPDATE
                    SET total_minor = spending_by_category.total_minor + EXCLUDED.total_minor,
                        account_name = EXCLUDED.account_name,
                        updated_at = EXCLUDED.updated_at
                    """)
                    .param("accountId", entry.accountId())
                    .param("name", entry.accountName())
                    .param("amount", entry.amountMinor())
                    .param("now", Timestamp.from(now))
                    .update();
            jdbc.sql("""
                    INSERT INTO spending_by_month (month, total_minor, updated_at)
                    VALUES (:month, :amount, :now)
                    ON CONFLICT (month) DO UPDATE
                    SET total_minor = spending_by_month.total_minor + EXCLUDED.total_minor,
                        updated_at = EXCLUDED.updated_at
                    """)
                    .param("month", month)
                    .param("amount", entry.amountMinor())
                    .param("now", Timestamp.from(now))
                    .update();
        }
        return true;
    }
}
