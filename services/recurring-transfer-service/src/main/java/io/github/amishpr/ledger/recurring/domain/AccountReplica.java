package io.github.amishpr.ledger.recurring.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** This service's read-only copy of a ledger account. Written only by replication. */
@Entity
@Table(name = "account_replica")
public class AccountReplica {

    public static final String ASSET = "ASSET";

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 16)
    private String type;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "replicated_at", nullable = false)
    private Instant replicatedAt;

    protected AccountReplica() {}

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isAsset() {
        return ASSET.equals(type);
    }
}
