package com.hardik.billing.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ledger_accounts")
public class LedgerAccount {

    public enum Type {
        ASSET,
        REVENUE,
        CONTRA_REVENUE
    }

    public static final String ACCOUNTS_RECEIVABLE = "ACCOUNTS_RECEIVABLE";
    public static final String CASH = "CASH";
    public static final String REVENUE = "REVENUE";
    public static final String REFUNDS = "REFUNDS";

    @Id
    private Short id;

    @Column(nullable = false, length = 40)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    protected LedgerAccount() {
    }

    public String getCode() {
        return code;
    }

    public Type getType() {
        return type;
    }
}
