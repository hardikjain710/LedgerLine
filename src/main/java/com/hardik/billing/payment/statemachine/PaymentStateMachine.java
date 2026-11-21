package com.hardik.billing.payment.statemachine;

import com.hardik.billing.common.BillingException;
import com.hardik.billing.common.ErrorCode;
import com.hardik.billing.payment.PaymentStatus;

public final class PaymentStateMachine {

    private PaymentStateMachine() {
    }

    public static PaymentStatus transition(PaymentStatus from, PaymentStatus to) {
        if (!canTransition(from, to)) {
            throw new BillingException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot transition payment from " + from + " to " + to + ".");
        }
        return to;
    }

    public static boolean canTransition(PaymentStatus from, PaymentStatus to) {
        return switch (from) {
            case CREATED -> to == PaymentStatus.AUTHORIZED || to == PaymentStatus.FAILED || to == PaymentStatus.VOIDED;
            case AUTHORIZED -> to == PaymentStatus.CAPTURED || to == PaymentStatus.VOIDED;
            case CAPTURED -> to == PaymentStatus.REFUNDED;
            case REFUNDED, FAILED, VOIDED -> false;
        };
    }
}
