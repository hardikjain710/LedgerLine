package com.hardik.billing.ledger.dto;

import com.hardik.billing.ledger.LedgerDirection;

public record LedgerEntryData(String accountCode, LedgerDirection direction, long amount) {
}
