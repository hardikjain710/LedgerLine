package com.hardik.billing.ledger.dto;

import com.hardik.billing.ledger.LedgerKind;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LedgerTransactionResponse(
        UUID id, LedgerKind kind, UUID referenceId, String currency, Instant createdAt, List<LedgerEntryData> entries
) {
}
