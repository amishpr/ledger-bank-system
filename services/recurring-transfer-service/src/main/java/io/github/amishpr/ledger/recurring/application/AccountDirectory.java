package io.github.amishpr.ledger.recurring.application;

import io.github.amishpr.ledger.recurring.client.LedgerClient;
import io.github.amishpr.ledger.recurring.client.LedgerGateway;
import io.github.amishpr.ledger.recurring.domain.AccountReplica;
import io.github.amishpr.ledger.recurring.domain.RecurringException;
import io.github.amishpr.ledger.recurring.repository.AccountReplicaRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers "what is account X" from the local replica first. The replica is
 * fed by events, so an account opened a moment ago may not be in it yet; in
 * that case this asks the ledger once over REST and stores the answer.
 */
@Component
public class AccountDirectory {

    private final AccountReplicaRepository replicas;
    private final LedgerGateway ledger;
    private final Clock clock;

    AccountDirectory(AccountReplicaRepository replicas, LedgerGateway ledger, Clock clock) {
        this.replicas = replicas;
        this.ledger = ledger;
        this.clock = clock;
    }

    @Transactional
    public AccountReplica require(UUID id) {
        return replicas.findById(id).orElseGet(() -> fetchFromLedger(id));
    }

    @Transactional(readOnly = true)
    public Map<UUID, AccountReplica> findAll(Collection<UUID> ids) {
        return replicas.findAllById(ids).stream().collect(Collectors.toMap(AccountReplica::getId, Function.identity()));
    }

    @Transactional
    public void replicate(UUID id, String name, String type, String currency, Instant createdAt) {
        replicas.upsert(id, name, type, currency, createdAt, clock.instant());
    }

    private AccountReplica fetchFromLedger(UUID id) {
        LedgerClient.Account account;
        try {
            account = ledger.findAccount(id).orElseThrow(() -> RecurringException.accountNotFound(id));
        } catch (LedgerGateway.LedgerUnavailableException e) {
            throw RecurringException.ledgerUnavailable();
        }
        replicate(account.id(), account.name(), account.type(), account.currency(), account.createdAt());
        return replicas.findById(id).orElseThrow();
    }
}
