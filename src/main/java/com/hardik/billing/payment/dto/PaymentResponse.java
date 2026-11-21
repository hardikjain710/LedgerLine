package com.hardik.billing.payment.dto;

import com.hardik.billing.payment.Payment;
import com.hardik.billing.payment.PaymentProcessorType;
import com.hardik.billing.payment.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID invoiceId,
        long amount,
        String currency,
        PaymentStatus status,
        PaymentProcessorType processor,
        String processorReference,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getInvoiceId(), payment.getAmount(),
                payment.getCurrency(), payment.getStatus(), payment.getProcessor(), payment.getProcessorReference(),
                payment.getFailureReason(), payment.getCreatedAt(), payment.getUpdatedAt());
    }
}
