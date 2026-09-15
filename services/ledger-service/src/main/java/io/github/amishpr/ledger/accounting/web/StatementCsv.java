package io.github.amishpr.ledger.accounting.web;

import io.github.amishpr.ledger.accounting.application.StatementService.StatementLine;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Builds the statement download. Same columns as the original API, oldest
 * first (the order a downloaded record is read in), CRLF line endings, and
 * amounts as plain dollars and cents with no float anywhere along the way.
 */
final class StatementCsv {

    private static final String HEADER = "Date,Description,Direction,Amount,Running Balance";
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private StatementCsv() {}

    /** {@code lines} arrive newest first, as the statement query returns them. */
    static String build(List<StatementLine> lines) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = lines.size() - 1; i >= 0; i--) {
            StatementLine line = lines.get(i);
            csv.append("\r\n")
                    .append(TIMESTAMP.format(line.createdAt())).append(',')
                    .append(escape(neutralizeFormula(line.description()))).append(',')
                    .append(line.direction()).append(',')
                    .append(dollars(line.amountMinor())).append(',')
                    .append(dollars(line.runningBalanceMinor()));
        }
        return csv.toString();
    }

    static String dollars(long amountMinor) {
        // Long.MIN_VALUE has no positive counterpart, so work from the
        // remainder's sign instead of negating the whole amount.
        String sign = amountMinor < 0 ? "-" : "";
        long whole = Math.abs(amountMinor / 100);
        long cents = Math.abs(amountMinor % 100);
        return sign + whole + "." + String.format(Locale.ROOT, "%02d", cents);
    }

    static String escape(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    /**
     * Spreadsheets run a cell that starts with = + - @ (or a tab or carriage
     * return) as a formula, so a description like {@code =HYPERLINK(...)}
     * could do something when the file is opened. A leading apostrophe makes
     * it plain text. Only free text gets this; amounts are produced here.
     */
    static String neutralizeFormula(String value) {
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            return "'" + value;
        }
        return value;
    }

    static String filenameFor(String accountName) {
        String slug = accountName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return (slug.isEmpty() ? "account" : slug) + "-statement.csv";
    }
}
