package io.github.amishpr.ledger.accounting.domain;

import io.github.amishpr.ledger.platform.id.Uuids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An account. It has no balance column on purpose: a balance is always the
 * sum of the account's journal entries, so there is exactly one source of
 * truth for how much money is in it.
 */
@Entity
@Table(name = "account")
public class Account extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountType type;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {}

    public static Account open(String name, AccountType type, String currency, Instant at) {
        Account account = new Account();
        account.id = Uuids.v7();
        account.name = Objects.requireNonNull(name).strip();
        account.type = Objects.requireNonNull(type);
        account.currency = Objects.requireNonNull(currency);
        account.createdAt = Objects.requireNonNull(at);
        return account;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public AccountType getType() {
        return type;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
