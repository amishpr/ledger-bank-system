package io.github.amishpr.ledger.recurring.repository;

import io.github.amishpr.ledger.recurring.domain.AccountReplica;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountReplicaRepository extends JpaRepository<AccountReplica, UUID> {

    Optional<AccountReplica> findFirstByNameOrderByCreatedAtAsc(String name);

    /**
     * Insert or refresh one account. An upsert makes replication naturally
     * idempotent: applying the same event twice leaves the same row.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO account_replica (id, name, type, currency, created_at, replicated_at)
            VALUES (:id, :name, :type, :currency, :createdAt, :now)
            ON CONFLICT (id) DO UPDATE
            SET name = EXCLUDED.name, type = EXCLUDED.type, currency = EXCLUDED.currency,
                replicated_at = EXCLUDED.replicated_at
            """)
    void upsert(
            @Param("id") UUID id,
            @Param("name") String name,
            @Param("type") String type,
            @Param("currency") String currency,
            @Param("createdAt") Instant createdAt,
            @Param("now") Instant now);
}
