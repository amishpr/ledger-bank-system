package io.github.amishpr.ledger.testsupport;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Empties every table a service owns between tests, except Flyway's history,
 * so tests can share one migrated schema without leaking rows into each other.
 */
public final class DatabaseCleaner {

    private DatabaseCleaner() {}

    public static void truncateAll(JdbcTemplate jdbc) {
        List<String> tables = jdbc.queryForList(
                """
                SELECT quote_ident(tablename) FROM pg_tables
                WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                """,
                String.class);
        if (!tables.isEmpty()) {
            jdbc.execute("TRUNCATE " + tables.stream().collect(Collectors.joining(", ")) + " RESTART IDENTITY CASCADE");
        }
    }
}
