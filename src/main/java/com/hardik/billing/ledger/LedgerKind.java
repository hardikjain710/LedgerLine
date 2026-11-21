package com.hardik.billing.ledger;

import com.hardik.billing.ledger.dto.LedgerEntryData;

import java.util.List;

import static com.hardik.billing.ledger.LedgerAccount.ACCOUNTS_RECEIVABLE;
import static com.hardik.billing.ledger.LedgerAccount.CASH;
import static com.hardik.billing.ledger.LedgerAccount.REFUNDS;
import static com.hardik.billing.ledger.LedgerAccount.REVENUE;

public enum LedgerKind {
    INVOICE_FINALIZED(ACCOUNTS_RECEIVABLE, REVENUE),
    INVOICE_VOIDED(REVENUE, ACCOUNTS_RECEIVABLE),
    PAYMENT_CAPTURED(CASH, ACCOUNTS_RECEIVABLE),
    PAYMENT_REFUNDED(REFUNDS, CASH);

    private final String debitAccount;
    private final String creditAccount;

    LedgerKind(String debitAccount, String creditAccount) {
        this.debitAccount = debitAccount;
        this.creditAccount = creditAccount;
    }

    public List<LedgerEntryData> entries(long amount) {
        return List.of(new LedgerEntryData(debitAccount, LedgerDirection.DEBIT, amount),
                new LedgerEntryData(creditAccount, LedgerDirection.CREDIT, amount));
    }
}
