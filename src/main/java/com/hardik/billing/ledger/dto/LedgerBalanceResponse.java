package com.hardik.billing.ledger.dto;

public record LedgerBalanceResponse(String accountCode, String currency, long balance) {
}
