package com.hardik.billing.invoice.dto;

import com.hardik.billing.invoice.InvoiceStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InvoiceResponse(
        UUID id,
        UUID customerId,
        InvoiceStatus status,
        String currency,
        long totalAmount,
        List<LineItem> lineItems,
        Instant createdAt,
        Instant finalizedAt
) {
    public record LineItem(UUID id, String description, int quantity, long unitAmount, int lineNumber) {
    }
}
