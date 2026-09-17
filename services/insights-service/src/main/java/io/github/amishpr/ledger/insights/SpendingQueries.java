package io.github.amishpr.ledger.insights;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class SpendingQueries {

    record CategoryTotal(UUID accountId, String accountName, long totalMinor) {}

    record MonthTotal(String month, long totalMinor) {}

    private final JdbcClient jdbc;

    SpendingQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Biggest first, ties broken by name so the order is stable. */
    List<CategoryTotal> byCategory() {
        return jdbc.sql("""
                SELECT account_id, account_name, total_minor FROM spending_by_category
                ORDER BY total_minor DESC, account_name
                """)
                .query((rs, row) -> new CategoryTotal(
                        rs.getObject("account_id", UUID.class), rs.getString("account_name"), rs.getLong("total_minor")))
                .list();
    }

    /** Oldest month first. */
    List<MonthTotal> byMonth() {
        return jdbc.sql("SELECT month, total_minor FROM spending_by_month ORDER BY month")
                .query((rs, row) -> new MonthTotal(rs.getString("month"), rs.getLong("total_minor")))
                .list();
    }
}
