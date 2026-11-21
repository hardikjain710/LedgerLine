package com.hardik.billing.ledger;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "ledger_transactions")
public class LedgerTransaction {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private LedgerKind kind;

    @Column(nullable = false)
    private UUID referenceId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "transaction", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @OrderBy("id ASC")
    private List<LedgerEntry> entries = new ArrayList<>();

    protected LedgerTransaction() {
    }

    public LedgerTransaction(LedgerKind kind, UUID referenceId, String currency, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.kind = kind;
        this.referenceId = referenceId;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public void addEntry(LedgerAccount account, LedgerDirection direction, long amount) {
        entries.add(new LedgerEntry(this, account, direction, amount));
    }

    public UUID getId() {
        return id;
    }

    public LedgerKind getKind() {
        return kind;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<LedgerEntry> getEntries() {
        return List.copyOf(entries);
    }
}
