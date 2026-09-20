package io.github.amishpr.ledger.recurring.application;

import io.github.amishpr.ledger.recurring.domain.AccountReplica;
import io.github.amishpr.ledger.recurring.domain.RecurrenceInterval;
import io.github.amishpr.ledger.recurring.domain.RecurringException;
import io.github.amishpr.ledger.recurring.domain.RecurringTransfer;
import io.github.amishpr.ledger.recurring.repository.RecurringTransferRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class RecurringTransferService {

    /** A schedule with the two accounts it moves money between. */
    public record ScheduleView(RecurringTransfer transfer, AccountReplica from, AccountReplica to) {}

    public record NewSchedule(
            String description, UUID fromAccountId, UUID toAccountId, long amountMinor, RecurrenceInterval interval, Instant startAt) {}

    private final RecurringTransferRepository schedules;
    private final AccountDirectory accounts;
    private final Clock clock;

    RecurringTransferService(RecurringTransferRepository schedules, AccountDirectory accounts, Clock clock) {
        this.schedules = schedules;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ScheduleView> list() {
        List<RecurringTransfer> all = schedules.findAllByOrderByCreatedAtAscIdAsc();
        Set<UUID> ids = new HashSet<>();
        all.forEach(t -> {
            ids.add(t.getFromAccountId());
            ids.add(t.getToAccountId());
        });
        Map<UUID, AccountReplica> byId = accounts.findAll(ids);
        return all.stream()
                .map(t -> new ScheduleView(t, byId.get(t.getFromAccountId()), byId.get(t.getToAccountId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ScheduleView get(UUID id) {
        return view(require(id));
    }

    public ScheduleView create(NewSchedule request) {
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw RecurringException.sameAccount();
        }
        if (request.amountMinor() <= 0) {
            throw RecurringException.invalidAmount();
        }
        AccountReplica from = accounts.require(request.fromAccountId());
        AccountReplica to = accounts.require(request.toAccountId());
        if (!from.isAsset() || !to.isAsset()) {
            throw RecurringException.notAssetAccounts();
        }
        Instant now = clock.instant();
        RecurringTransfer transfer = RecurringTransfer.schedule(
                request.description(),
                from.getId(),
                to.getId(),
                request.amountMinor(),
                request.interval(),
                request.startAt() == null ? now : request.startAt(),
                now);
        schedules.save(transfer);
        return new ScheduleView(transfer, from, to);
    }

    /** Pauses a running schedule or resumes a paused one. */
    public ScheduleView toggle(UUID id) {
        RecurringTransfer transfer = require(id);
        transfer.setActive(!transfer.isActive());
        return view(transfer);
    }

    /** The idempotent form of {@link #toggle}: the same request always leaves the same state. */
    public ScheduleView setActive(UUID id, boolean active) {
        RecurringTransfer transfer = require(id);
        transfer.setActive(active);
        return view(transfer);
    }

    /** Stops future occurrences. Transfers it already posted stay in the ledger. */
    public void delete(UUID id) {
        schedules.delete(require(id));
    }

    @Transactional(readOnly = true)
    public boolean isEmpty() {
        return schedules.count() == 0;
    }

    private RecurringTransfer require(UUID id) {
        return schedules.findById(id).orElseThrow(() -> RecurringException.notFound(id));
    }

    private ScheduleView view(RecurringTransfer t) {
        Map<UUID, AccountReplica> byId = accounts.findAll(List.of(t.getFromAccountId(), t.getToAccountId()));
        return new ScheduleView(t, byId.get(t.getFromAccountId()), byId.get(t.getToAccountId()));
    }
}
