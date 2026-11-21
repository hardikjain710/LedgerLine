package com.hardik.billing.payment;

import java.util.Set;

public enum PaymentStatus {
    CREATED,
    AUTHORIZED,
    CAPTURED,
    REFUNDED,
    FAILED,
    VOIDED;

    public static final Set<PaymentStatus> ACTIVE = Set.of(CREATED, AUTHORIZED, CAPTURED);
}
