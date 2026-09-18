package io.github.amishpr.ledger.recurring.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecurrenceIntervalTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static Instant local(String dateTime) {
        return ZonedDateTime.parse(dateTime + "-05:00[America/New_York]").toInstant();
    }

    @Test
    void weeklyAddsSevenDays() {
        Instant start = Instant.parse("2026-03-01T00:00:00Z");

        assertThat(RecurrenceInterval.WEEKLY.nextAfter(start, start, ZoneId.of("UTC")))
                .isEqualTo(Instant.parse("2026-03-08T00:00:00Z"));
    }

    @Test
    void everyMinuteAddsOneMinute() {
        Instant start = Instant.parse("2026-03-01T00:00:00Z");

        assertThat(RecurrenceInterval.EVERY_MINUTE.nextAfter(start, start, NEW_YORK))
                .isEqualTo(Instant.parse("2026-03-01T00:01:00Z"));
    }

    @Test
    void dailyKeepsTheSameWallClockTimeAcrossDaylightSaving() {
        // The night of Mar 7 2026 New York moves its clocks forward an hour.
        Instant nineAm = local("2026-03-07T09:00:00");

        Instant next = RecurrenceInterval.DAILY.nextAfter(nineAm, nineAm, NEW_YORK);

        assertThat(next.atZone(NEW_YORK).getHour()).isEqualTo(9);
        assertThat(next).isEqualTo(Instant.parse("2026-03-08T13:00:00Z"));
    }

    @Test
    void monthlyFromThe31stLandsOnMonthEndsAndComesBack() {
        Instant anchor = Instant.parse("2026-01-31T15:00:00Z");
        List<String> runs = new ArrayList<>();
        Instant previous = anchor;
        for (int i = 0; i < 5; i++) {
            previous = RecurrenceInterval.MONTHLY.nextAfter(anchor, previous, ZoneId.of("UTC"));
            runs.add(previous.toString().substring(0, 10));
        }

        // Adding a month to the previous run would drift to the 28th for good.
        assertThat(runs).containsExactly("2026-02-28", "2026-03-31", "2026-04-30", "2026-05-31", "2026-06-30");
    }

    @Test
    void monthlyCatchesUpFromAnOldPreviousRun() {
        Instant anchor = Instant.parse("2026-01-15T12:00:00Z");
        Instant previous = Instant.parse("2026-04-15T12:00:00Z");

        assertThat(RecurrenceInterval.MONTHLY.nextAfter(anchor, previous, ZoneId.of("UTC")))
                .isEqualTo(Instant.parse("2026-05-15T12:00:00Z"));
    }
}
