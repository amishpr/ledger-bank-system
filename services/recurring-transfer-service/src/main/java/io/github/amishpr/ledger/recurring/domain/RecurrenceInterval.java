package io.github.amishpr.ledger.recurring.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * How often a schedule repeats. The arithmetic is calendar arithmetic in the
 * schedule's time zone: "daily" keeps the same wall clock time across a
 * daylight saving change, and "monthly" means the same day of the month.
 */
public enum RecurrenceInterval {
    /** For demos, so a schedule can be watched running without waiting a day. */
    EVERY_MINUTE,
    DAILY,
    WEEKLY,
    MONTHLY;

    /**
     * The first occurrence after {@code previous}.
     *
     * <p>Monthly schedules count whole months from the {@code anchor} (the first
     * occurrence) instead of adding a month to the previous run. Adding to the
     * previous run would drift: Jan 31, Feb 28, Mar 28, Apr 28 and so on. Counting
     * from the anchor gives Jan 31, Feb 28, Mar 31, Apr 30.
     */
    public Instant nextAfter(Instant anchor, Instant previous, ZoneId zone) {
        ZonedDateTime last = previous.atZone(zone);
        return switch (this) {
            case EVERY_MINUTE -> previous.plus(1, ChronoUnit.MINUTES);
            case DAILY -> last.plusDays(1).toInstant();
            case WEEKLY -> last.plusWeeks(1).toInstant();
            case MONTHLY -> {
                ZonedDateTime start = anchor.atZone(zone);
                long months = Math.max(0, ChronoUnit.MONTHS.between(start, last));
                ZonedDateTime candidate = start.plusMonths(months);
                while (!candidate.toInstant().isAfter(previous)) {
                    months++;
                    candidate = start.plusMonths(months);
                }
                yield candidate.toInstant();
            }
        };
    }
}
