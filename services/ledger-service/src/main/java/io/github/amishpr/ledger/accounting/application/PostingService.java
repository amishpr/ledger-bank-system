package io.github.amishpr.ledger.accounting.application;

import io.github.amishpr.ledger.accounting.domain.LedgerException;
import io.github.amishpr.ledger.accounting.domain.LedgerTransaction;
import io.github.amishpr.ledger.accounting.domain.PostingLine;
import io.github.amishpr.ledger.accounting.domain.PostingRules;
import io.github.amishpr.ledger.accounting.repository.LedgerTransactionRepository;
import io.github.amishpr.ledger.platform.web.ApiException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Posts and reverses transactions. Transaction boundaries are drawn here with
 * a {@link TransactionTemplate} rather than annotations, because the
 * idempotency race below needs a second, separate transaction after the first
 * one fails, and that has to be explicit.
 */
@Service
public class PostingService {

    private final Journal journal;
    private final LedgerEvents events;
    private final LedgerTransactionRepository transactions;
    private final TransactionTemplate transactionTemplate;
    private final ObservationRegistry observations;
    private final Clock clock;

    PostingService(
            Journal journal,
            LedgerEvents events,
            LedgerTransactionRepository transactions,
            TransactionTemplate transactionTemplate,
            ObservationRegistry observations,
            Clock clock) {
        this.journal = journal;
        this.events = events;
        this.transactions = transactions;
        this.transactionTemplate = transactionTemplate;
        this.observations = observations;
        this.clock = clock;
    }

    public PostingResult post(PostTransactionCommand command) {
        return observed("api", command, () -> postAt(command, clock.instant()));
    }

    /**
     * Posts with an explicit timestamp. Only the demo seeder may call this, to
     * build a year of believable history; the REST API never accepts a
     * timestamp from a client, so nobody can lie about when money moved. An
     * architecture test enforces who calls it.
     */
    public PostingResult postBackdated(PostTransactionCommand command, Instant at) {
        return postAt(command, at);
    }

    public PostingResult reverse(UUID transactionId, String note) {
        Observation observation = Observation.createNotStarted("ledger.transactions.reverse", observations);
        return observation.observe(() -> transactionTemplate.execute(status -> {
            LedgerTransaction original = transactions.lockById(transactionId)
                    .orElseThrow(() -> LedgerException.transactionNotFound(transactionId));
            if (original.isVoided()) {
                throw LedgerException.alreadyVoided(transactionId);
            }
            List<PostingLine> lines = original.reversingLines();
            PostingRules.validate(lines);

            String description = note != null
                    ? note
                    : "Reversal of " + original.getId() + ": " + original.getDescription();
            Journal.Written written = journal.write(description, lines, null, null, null, original.getId(), clock.instant());
            original.markVoided();
            events.transactionReversed(written, original.getId());
            return PostingResult.created(written.transaction());
        }));
    }

    public Optional<LedgerTransaction> find(UUID transactionId) {
        return transactions.findWithEntriesById(transactionId);
    }

    private PostingResult postAt(PostTransactionCommand command, Instant at) {
        PostingRules.validate(command.lines());
        String key = command.idempotencyKey();
        String hash = key == null ? null : RequestHasher.hash(command.description(), command.lines());

        if (key != null) {
            Optional<PostingResult> replay = findReplay(key, hash);
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        try {
            return transactionTemplate.execute(status -> {
                Journal.Written written =
                        journal.write(command.description(), command.lines(), key, hash, command.origin(), null, at);
                events.transactionPosted(written);
                return PostingResult.created(written.transaction());
            });
        } catch (DataIntegrityViolationException e) {
            // Two requests with the same key got past the lookup at the same
            // moment and the unique index stopped the second insert. That
            // transaction is already rolled back; settle it in a fresh one by
            // returning whatever the winner posted.
            if (key == null) {
                throw e;
            }
            return findReplay(key, hash).orElseThrow(() -> e);
        }
    }

    private Optional<PostingResult> findReplay(String key, String hash) {
        return transactionTemplate.execute(status -> transactions.findByIdempotencyKey(key).map(existing -> {
            if (!existing.getRequestHash().equals(hash)) {
                throw LedgerException.idempotencyConflict(key);
            }
            return PostingResult.replayed(existing);
        }));
    }

    private PostingResult observed(String channel, PostTransactionCommand command, Supplier<PostingResult> posting) {
        Observation observation = Observation.createNotStarted("ledger.transactions.post", observations)
                .lowCardinalityKeyValue("origin", command.origin() == null ? channel : command.origin().type().name());
        return observation.observe(() -> {
            try {
                PostingResult result = posting.get();
                observation.lowCardinalityKeyValue("outcome", result.replayed() ? "replayed" : "posted");
                return result;
            } catch (ApiException e) {
                // The error code is a small fixed set, so it is safe as a metric tag.
                observation.lowCardinalityKeyValue("outcome", e.code());
                throw e;
            }
        });
    }
}
