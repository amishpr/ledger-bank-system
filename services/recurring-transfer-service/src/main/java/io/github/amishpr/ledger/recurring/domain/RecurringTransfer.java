package io.github.amishpr.ledger.recurring.domain;

import io.github.amishpr.ledger.platform.id.Uuids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * A standing instruction to move the same amount between two asset accounts
 * on a schedule. It never touches money itself; each occurrence is posted
 * through the ledger like any other transfer, with every rule still applied.
 *
 * <p>{@code version} gives optimistic locking: if someone pauses a schedule
 * while the sweep is recording a run, one of the two writes is rejected rather
 * than silently overwriting the other.
 */
@Entity
@Table(name = "recurring_transfer")
public class RecurringTransfer {

    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    private UUID id;

    @Column(nullable = false, length = 280)
    private String description;

    @Column(name = "from_account_id", nullable = false, updatable = false)
    private UUID fromAccountId;

    @Column(name = "to_account_id", nullable = false, updatable = false)
    private UUID toAccountId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Enumerated(EnumType.STRING)
    @Column(name = "recurrence", nullable = false, length = 16, updatable = false)
    private RecurrenceInterval interval;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "anchor_at", nullable = false, updatable = false)
    private Instant anchorAt;

    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_run_status", length = 16)
    private RunStatus lastRunStatus;

    @Column(name = "last_run_error", length = MAX_ERROR_LENGTH)
    private String lastRunError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected RecurringTransfer() {}

    public static RecurringTransfer schedule(
            String description,
            UUID fromAccountId,
            UUID toAccountId,
            long amountMinor,
            RecurrenceInterval interval,
            Instant firstRunAt,
            Instant now) {
        if (fromAccountId.equals(toAccountId)) {
            throw RecurringException.sameAccount();
        }
        if (amountMinor <= 0) {
            throw RecurringException.invalidAmount();
        }
        RecurringTransfer transfer = new RecurringTransfer();
        transfer.id = Uuids.v7();
        transfer.description = Objects.requireNonNull(description).strip();
        transfer.fromAccountId = fromAccountId;
        transfer.toAccountId = toAccountId;
        transfer.amountMinor = amountMinor;
        transfer.interval = Objects.requireNonNull(interval);
        transfer.active = true;
        // Whole milliseconds, so the occurrence key reads back from Postgres
        // exactly as it was first sent.
        transfer.anchorAt = Objects.requireNonNull(firstRunAt).truncatedTo(ChronoUnit.MILLIS);
        transfer.nextRunAt = transfer.anchorAt;
        transfer.createdAt = Objects.requireNonNull(now);
        return transfer;
    }

    /**
     * The idempotency key for the occurrence that is due now. It is derived
     * from the schedule and the due time, never generated, so a retry after a
     * timeout, a restart, or two sweeps racing all send the same key and the
     * ledger posts the occurrence at most once.
     */
    public String occurrenceKey() {
        return "recurring:" + id + ":" + nextRunAt;
    }

    public void recordSuccess(Instant now, ZoneId zone) {
        lastRunAt = now;
        lastRunStatus = RunStatus.SUCCESS;
        lastRunError = null;
        advance(zone);
    }

    /**
     * The ledger refused this occurrence, most often for insufficient funds.
     * Skip it rather than retry every sweep, since the same refusal would just
     * repeat. The next occurrence gets a fresh try.
     */
    public void recordRejection(Instant now, String error, ZoneId zone) {
        lastRunAt = now;
        lastRunStatus = RunStatus.FAILED;
        lastRunError = truncate(error);
        advance(zone);
    }

    /**
     * The ledger could not be reached. The occurrence stays due and the next
     * sweep tries it again with the same key.
     */
    public void recordUnavailable(Instant now, String error) {
        lastRunAt = now;
        lastRunStatus = RunStatus.FAILED;
        lastRunError = truncate(error);
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    private void advance(ZoneId zone) {
        nextRunAt = interval.nextAfter(anchorAt, nextRunAt, zone);
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH - 3) + "...";
    }

    public UUID getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public UUID getFromAccountId() {
        return fromAccountId;
    }

    public UUID getToAccountId() {
        return toAccountId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public RecurrenceInterval getInterval() {
        return interval;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getAnchorAt() {
        return anchorAt;
    }

    public Instant getNextRunAt() {
        return nextRunAt;
    }

    public Instant getLastRunAt() {
        return lastRunAt;
    }

    public RunStatus getLastRunStatus() {
        return lastRunStatus;
    }

    public String getLastRunError() {
        return lastRunError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
