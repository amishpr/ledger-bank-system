package io.github.amishpr.ledger.recurring.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecurringTransferTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private final UUID checking = UUID.randomUUID();
    private final UUID savings = UUID.randomUUID();
    private final Instant due = Instant.parse("2026-01-01T00:00:00.123456789Z");

    private RecurringTransfer daily() {
        return RecurringTransfer.schedule("Sweep", checking, savings, 1_000, RecurrenceInterval.DAILY, due, due);
    }

    @Test
    void derivesTheSameKeyForTheSameOccurrenceEveryTime() {
        RecurringTransfer transfer = daily();

        assertThat(transfer.occurrenceKey())
                .isEqualTo(transfer.occurrenceKey())
                .startsWith("recurring:" + transfer.getId() + ":")
                // Truncated to milliseconds so it survives a round trip through Postgres.
                .endsWith("2026-01-01T00:00:00.123Z");
    }

    @Test
    void movesToTheNextOccurrenceAfterASuccessOrARejection() {
        RecurringTransfer transfer = daily();
        String firstKey = transfer.occurrenceKey();

        transfer.recordRejection(due, "Account has insufficient funds", UTC);

        assertThat(transfer.getLastRunStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(transfer.getNextRunAt()).isEqualTo(Instant.parse("2026-01-02T00:00:00.123Z"));
        assertThat(transfer.occurrenceKey()).isNotEqualTo(firstKey);

        transfer.recordSuccess(due, UTC);
        assertThat(transfer.getLastRunStatus()).isEqualTo(RunStatus.SUCCESS);
        assertThat(transfer.getLastRunError()).isNull();
    }

    @Test
    void staysDueWhenTheLedgerCouldNotBeReached() {
        RecurringTransfer transfer = daily();
        String key = transfer.occurrenceKey();

        transfer.recordUnavailable(due, "The ledger did not respond");

        assertThat(transfer.getNextRunAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00.123Z"));
        assertThat(transfer.occurrenceKey()).isEqualTo(key);
    }

    @Test
    void refusesASameAccountScheduleOrANonPositiveAmount() {
        assertThatThrownBy(() -> RecurringTransfer.schedule("x", checking, checking, 1, RecurrenceInterval.DAILY, due, due))
                .hasMessageContaining("must be different");
        assertThatThrownBy(() -> RecurringTransfer.schedule("x", checking, savings, 0, RecurrenceInterval.DAILY, due, due))
                .hasMessageContaining("greater than zero");
    }
}
