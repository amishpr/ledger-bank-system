package io.github.amishpr.ledger.recurring.seed;

import io.github.amishpr.ledger.recurring.application.RecurringTransferService;
import io.github.amishpr.ledger.recurring.application.RecurringTransferService.NewSchedule;
import io.github.amishpr.ledger.recurring.domain.AccountReplica;
import io.github.amishpr.ledger.recurring.domain.RecurrenceInterval;
import io.github.amishpr.ledger.recurring.repository.AccountReplicaRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Schedules the demo's "Automatic savings sweep", due immediately, so the
 * first sweep after startup posts it and the background job can be watched
 * doing something.
 *
 * <p>The accounts it needs are seeded by the ledger service and reach this
 * service as events, so it waits for them to appear in the replica instead
 * of assuming an order the two services start in.
 */
@Component
@ConditionalOnBooleanProperty("recurring.seed.enabled")
class RecurringDemoSeeder {

    private static final Logger log = LoggerFactory.getLogger(RecurringDemoSeeder.class);
    private static final Duration GIVE_UP_AFTER = Duration.ofMinutes(5);

    private final RecurringTransferService schedules;
    private final AccountReplicaRepository replicas;

    RecurringDemoSeeder(RecurringTransferService schedules, AccountReplicaRepository replicas) {
        this.schedules = schedules;
        this.replicas = replicas;
    }

    @EventListener(ApplicationReadyEvent.class)
    void seedInBackground() {
        Thread.ofVirtual().name("recurring-demo-seed").start(this::seedWhenAccountsArrive);
    }

    void seedWhenAccountsArrive() {
        Instant deadline = Instant.now().plus(GIVE_UP_AFTER);
        while (Instant.now().isBefore(deadline)) {
            if (!schedules.isEmpty()) {
                return;
            }
            Optional<AccountReplica> checking = replicas.findFirstByNameOrderByCreatedAtAsc("Checking - Alex");
            Optional<AccountReplica> savings = replicas.findFirstByNameOrderByCreatedAtAsc("Savings - Alex");
            if (checking.isPresent() && savings.isPresent()) {
                schedules.create(new NewSchedule(
                        "Automatic savings sweep",
                        checking.get().getId(),
                        savings.get().getId(),
                        5_000,
                        RecurrenceInterval.WEEKLY,
                        null));
                log.info("Scheduled the demo savings sweep");
                return;
            }
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Demo accounts never arrived from the ledger, so the demo schedule was not created");
    }
}
