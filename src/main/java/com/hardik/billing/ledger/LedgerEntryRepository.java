package com.hardik.billing.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    interface DirectionTotal {
        String getAccountCode();

        String getCurrency();

        String getDirection();

        long getAmount();
    }

    @Query(value = """
            SELECT a.code AS "accountCode", t.currency AS currency, e.direction AS direction,
                   CAST(SUM(e.amount) AS bigint) AS amount
            FROM ledger_entries e
            JOIN ledger_accounts a ON a.id = e.account_id
            JOIN ledger_transactions t ON t.id = e.transaction_id
            GROUP BY a.code, t.currency, e.direction
            """, nativeQuery = true)
    List<DirectionTotal> sumByAccountCurrencyAndDirection();
}
