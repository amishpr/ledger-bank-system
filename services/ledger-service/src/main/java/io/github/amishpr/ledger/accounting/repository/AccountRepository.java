package io.github.amishpr.ledger.accounting.repository;

import io.github.amishpr.ledger.accounting.domain.Account;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findAllByOrderByCreatedAtAscIdAsc();

    /**
     * Locks the accounts a posting touches, always in id order. Two postings
     * that share accounts therefore queue up instead of each reading a balance
     * the other is about to change, and because every caller takes the locks
     * in the same order, they cannot deadlock on each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id in :ids order by a.id")
    List<Account> lockAllById(@Param("ids") Collection<UUID> ids);
}
