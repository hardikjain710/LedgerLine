package com.hardik.billing.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private LedgerTransaction transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private LedgerAccount account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private LedgerDirection direction;

    @Column(nullable = false)
    private long amount;

    protected LedgerEntry() {
    }

    LedgerEntry(LedgerTransaction transaction, LedgerAccount account, LedgerDirection direction, long amount) {
        this.transaction = transaction;
        this.account = account;
        this.direction = direction;
        this.amount = amount;
    }

    public LedgerAccount getAccount() {
        return account;
    }

    public LedgerDirection getDirection() {
        return direction;
    }

    public long getAmount() {
        return amount;
    }
}
