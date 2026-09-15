package io.github.amishpr.ledger.accounting.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amishpr.ledger.accounting.application.StatementService.StatementLine;
import io.github.amishpr.ledger.accounting.domain.EntryDirection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StatementCsvTest {

    @ParameterizedTest
    @CsvSource({"0, 0.00", "5, 0.05", "1050, 10.50", "-1050, -10.50", "-5, -0.05", "123456789, 1234567.89"})
    void formatsCentsAsDollarsWithoutFloatingPoint(long cents, String dollars) {
        assertThat(StatementCsv.dollars(cents)).isEqualTo(dollars);
    }

    @Test
    void handlesTheMostNegativeLongWithoutOverflowing() {
        assertThat(StatementCsv.dollars(Long.MIN_VALUE)).isEqualTo("-92233720368547758.08");
    }

    @Test
    void writesOldestFirstWithAHeaderAndCrlf() {
        StatementLine newest = line("Coffee shop", EntryDirection.CREDIT, 875, 99_125, "2026-10-01T09:30:00Z");
        StatementLine oldest = line("Opening balance", EntryDirection.DEBIT, 100_000, 100_000, "2026-09-01T12:00:00.250Z");

        String csv = StatementCsv.build(List.of(newest, oldest));

        assertThat(csv.split("\r\n")).containsExactly(
                "Date,Description,Direction,Amount,Running Balance",
                "2026-09-01T12:00:00.250Z,Opening balance,DEBIT,1000.00,1000.00",
                "2026-10-01T09:30:00.000Z,Coffee shop,CREDIT,8.75,991.25");
    }

    @Test
    void quotesFieldsThatContainCommasOrQuotes() {
        assertThat(StatementCsv.escape("Dinner, with \"friends\"")).isEqualTo("\"Dinner, with \"\"friends\"\"\"");
        assertThat(StatementCsv.escape("Plain")).isEqualTo("Plain");
    }

    @Test
    void stopsSpreadsheetsFromRunningADescriptionAsAFormula() {
        assertThat(StatementCsv.neutralizeFormula("=HYPERLINK(\"http://evil\")")).startsWith("'=");
        assertThat(StatementCsv.neutralizeFormula("+1")).isEqualTo("'+1");
        assertThat(StatementCsv.neutralizeFormula("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(StatementCsv.neutralizeFormula("Rent split - Alex to Jordan")).isEqualTo("Rent split - Alex to Jordan");
    }

    @Test
    void namesTheFileAfterTheAccount() {
        assertThat(StatementCsv.filenameFor("Checking - Alex")).isEqualTo("checking-alex-statement.csv");
        assertThat(StatementCsv.filenameFor("!!!")).isEqualTo("account-statement.csv");
    }

    private static StatementLine line(String description, EntryDirection direction, long amount, long running, String at) {
        return new StatementLine(UUID.randomUUID(), UUID.randomUUID(), description, direction, amount, running, Instant.parse(at));
    }
}
