package io.github.amishpr.ledger.recurring.repository;

import io.github.amishpr.ledger.recurring.domain.RecurringTransfer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecurringTransferRepository extends JpaRepository<RecurringTransfer, UUID> {

    List<RecurringTransfer> findAllByOrderByCreatedAtAscIdAsc();

    @Query("""
            select r from RecurringTransfer r
            where r.active = true and r.nextRunAt <= :now
            order by r.nextRunAt, r.id
            """)
    List<RecurringTransfer> findDue(@Param("now") Instant now, Limit limit);
}
