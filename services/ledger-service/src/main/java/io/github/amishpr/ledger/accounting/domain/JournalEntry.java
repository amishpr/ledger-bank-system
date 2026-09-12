package io.github.amishpr.ledger.accounting.domain;

import io.github.amishpr.ledger.platform.id.Uuids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One leg of a transaction: a debit or a credit of a positive amount against
 * one account. Append-only, enforced by a database trigger.
 */
@Entity
@Table(name = "journal_entry")
public class JournalEntry extends AssignedIdEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false, updatable = false)
    private LedgerTransaction transaction;

    @Column(name = "line_number", nullable = false, updatable = false)
    private short lineNumber;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6, updatable = false)
    private EntryDirection direction;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected JournalEntry() {}

    static JournalEntry of(LedgerTransaction transaction, int lineNumber, PostingLine line, Instant at) {
        JournalEntry entry = new JournalEntry();
        entry.id = Uuids.v7();
        entry.transaction = transaction;
        entry.lineNumber = (short) lineNumber;
        entry.accountId = line.accountId();
        entry.direction = line.direction();
        entry.amountMinor = line.amountMinor();
        entry.createdAt = at;
        return entry;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getTransactionId() {
        return transaction.getId();
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public EntryDirection getDirection() {
        return direction;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
