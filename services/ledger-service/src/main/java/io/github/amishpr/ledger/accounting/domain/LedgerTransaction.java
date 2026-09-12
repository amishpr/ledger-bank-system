package io.github.amishpr.ledger.accounting.domain;

import io.github.amishpr.ledger.platform.id.Uuids;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The atomic unit of the ledger: a set of entries that balance. Once written,
 * the only change it can ever go through is being voided by a reversal, and a
 * database trigger refuses anything else.
 */
@Entity
@Table(name = "ledger_transaction")
public class LedgerTransaction extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 500, updatable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransactionStatus status;

    @Column(name = "idempotency_key", length = 255, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", length = 64, updatable = false)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin_type", length = 40, updatable = false)
    private OriginType originType;

    @Column(name = "origin_reference", length = 100, updatable = false)
    private String originReference;

    @Column(name = "reversal_of", updatable = false)
    private UUID reversalOf;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.PERSIST)
    @OrderBy("lineNumber")
    private List<JournalEntry> entries = new ArrayList<>();

    protected LedgerTransaction() {}

    /**
     * Builds a transaction and its entries. Call {@link PostingRules#validate}
     * first; this constructor trusts its input.
     */
    public static LedgerTransaction post(
            String description,
            List<PostingLine> lines,
            String idempotencyKey,
            String requestHash,
            Origin origin,
            UUID reversalOf,
            Instant at) {
        LedgerTransaction tx = new LedgerTransaction();
        tx.id = Uuids.v7();
        tx.description = Objects.requireNonNull(description);
        tx.status = TransactionStatus.POSTED;
        tx.idempotencyKey = idempotencyKey;
        tx.requestHash = requestHash;
        tx.originType = origin == null ? null : origin.type();
        tx.originReference = origin == null ? null : origin.reference();
        tx.reversalOf = reversalOf;
        tx.createdAt = Objects.requireNonNull(at);
        for (int i = 0; i < lines.size(); i++) {
            tx.entries.add(JournalEntry.of(tx, i, lines.get(i), at));
        }
        return tx;
    }

    /** Marks this transaction as reversed. The reversing transaction holds the correcting entries. */
    public void markVoided() {
        if (status == TransactionStatus.VOIDED) {
            throw LedgerException.alreadyVoided(id);
        }
        status = TransactionStatus.VOIDED;
    }

    public boolean isVoided() {
        return status == TransactionStatus.VOIDED;
    }

    public List<PostingLine> reversingLines() {
        return entries.stream().map(e -> new PostingLine(e.getAccountId(), e.getDirection(), e.getAmountMinor()).flipped()).toList();
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Origin getOrigin() {
        return originType == null ? null : new Origin(originType, originReference);
    }

    public UUID getReversalOf() {
        return reversalOf;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<JournalEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    /** Distinct account ids in the order they first appear. */
    public List<UUID> affectedAccountIds() {
        return entries.stream().map(JournalEntry::getAccountId).distinct().toList();
    }
}
