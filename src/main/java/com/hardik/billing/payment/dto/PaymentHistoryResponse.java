package com.hardik.billing.payment.dto;

import com.hardik.billing.payment.PaymentStatus;
import com.hardik.billing.payment.PaymentStatusHistory;

import java.time.Instant;

public record PaymentHistoryResponse(
        long id,
        PaymentStatus fromStatus,
        PaymentStatus toStatus,
        String reason,
        PaymentStatusHistory.Actor actor,
        Instant createdAt
) {
    public static PaymentHistoryResponse from(PaymentStatusHistory history) {
        return new PaymentHistoryResponse(history.getId(), history.getFromStatus(), history.getToStatus(),
                history.getReason(), history.getActor(), history.getCreatedAt());
    }
}
