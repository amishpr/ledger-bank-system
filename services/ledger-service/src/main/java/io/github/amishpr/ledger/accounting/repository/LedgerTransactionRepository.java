package io.github.amishpr.ledger.accounting.repository;

import io.github.amishpr.ledger.accounting.domain.LedgerTransaction;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    @EntityGraph(attributePaths = "entries")
    Optional<LedgerTransaction> findWithEntriesById(UUID id);

    @EntityGraph(attributePaths = "entries")
    Optional<LedgerTransaction> findByIdempotencyKey(String idempotencyKey);

    /** Locks a transaction before reversing it, so two reversals of the same one cannot both succeed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from LedgerTransaction t where t.id = :id")
    Optional<LedgerTransaction> lockById(@Param("id") UUID id);
}
