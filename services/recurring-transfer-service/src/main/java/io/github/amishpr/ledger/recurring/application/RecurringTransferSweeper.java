package io.github.amishpr.ledger.recurring.application;

import io.github.amishpr.ledger.recurring.client.LedgerClient;
import io.github.amishpr.ledger.recurring.client.LedgerGateway;
import io.github.amishpr.ledger.recurring.client.LedgerGateway.PostingOutcome;
import io.github.amishpr.ledger.recurring.domain.RecurringTransfer;
import io.github.amishpr.ledger.recurring.repository.RecurringTransferRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The background job. Every sweep finds the schedules that are due and posts
 * each one through the ledger, with the same rules as a manual transfer.
 *
 * <p>ShedLock makes sure only one instance sweeps at a time. That fixes the gap
 * the original single-process scheduler admitted to: run two copies and both
 * would sweep. Even without the lock, the derived idempotency key would stop a
 * double posting; the lock just gives the schedule one clear owner.
 */
@Component
public class RecurringTransferSweeper {

    private static final Logger log = LoggerFactory.getLogger(RecurringTransferSweeper.class);
    private static final int BATCH = 100;

    public record SweepResult(UUID scheduleId, String outcome, String error) {}

    private final RecurringTransferRepository schedules;
    private final LedgerGateway ledger;
    private final RunRecorder recorder;
    private final MeterRegistry meters;
    private final Clock clock;

    RecurringTransferSweeper(
            RecurringTransferRepository schedules,
            LedgerGateway ledger,
            RunRecorder recorder,
            MeterRegistry meters,
            Clock clock) {
        this.schedules = schedules;
        this.ledger = ledger;
        this.recorder = recorder;
        this.meters = meters;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${recurring.sweep.interval:PT15S}", initialDelayString = "${recurring.sweep.initial-delay:PT5S}")
    @SchedulerLock(name = "recurring-transfer-sweep", lockAtMostFor = "PT2M", lockAtLeastFor = "PT1S")
    public void scheduledSweep() {
        try {
            List<SweepResult> results = sweep(clock.instant());
            if (!results.isEmpty()) {
                log.info("Recurring transfer sweep ran {} occurrence(s): {}", results.size(), results);
            }
        } catch (RuntimeException e) {
            log.error("Recurring transfer sweep failed", e);
        }
    }

    /**
     * Posts every occurrence due at {@code now}. Public so a test can drive it
     * directly and deterministically instead of waiting on the timer.
     */
    public List<SweepResult> sweep(Instant now) {
        return meters.timer("recurring.sweep").record(() -> {
            List<SweepResult> results = new ArrayList<>();
            for (RecurringTransfer due : schedules.findDue(now, org.springframework.data.domain.Limit.of(BATCH))) {
                results.add(runOccurrence(due));
            }
            return results;
        });
    }

    private SweepResult runOccurrence(RecurringTransfer due) {
        PostingOutcome outcome = ledger.post(due.occurrenceKey(), postRequest(due));
        String label = switch (outcome) {
            case PostingOutcome.Posted p -> "posted";
            case PostingOutcome.Rejected r -> "rejected";
            case PostingOutcome.Unavailable u -> "unavailable";
        };
        meters.counter("recurring.occurrences", "outcome", label).increment();
        try {
            if (!recorder.record(due.getId(), due.getNextRunAt(), outcome)) {
                label = "superseded";
            }
        } catch (ObjectOptimisticLockingFailureException e) {
            // Someone paused or edited the schedule at the same moment. The
            // next sweep sees the new state; the same key keeps it safe.
            label = "conflict";
        }
        String error = switch (outcome) {
            case PostingOutcome.Rejected r -> r.message();
            case PostingOutcome.Unavailable u -> u.reason();
            case PostingOutcome.Posted p -> null;
        };
        return new SweepResult(due.getId(), label, error);
    }

    private static LedgerClient.PostRequest postRequest(RecurringTransfer t) {
        String amount = Long.toString(t.getAmountMinor());
        return new LedgerClient.PostRequest(
                t.getDescription(),
                List.of(
                        new LedgerClient.Entry(t.getFromAccountId(), "CREDIT", amount),
                        new LedgerClient.Entry(t.getToAccountId(), "DEBIT", amount)),
                new LedgerClient.Origin("RECURRING_TRANSFER", t.getId().toString()));
    }
}
