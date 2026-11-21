package com.hardik.billing.invoice.statemachine;

import com.hardik.billing.common.BillingException;
import com.hardik.billing.common.ErrorCode;
import com.hardik.billing.invoice.InvoiceStatus;

public final class InvoiceStateMachine {

    private InvoiceStateMachine() {
    }

    public static InvoiceStatus transition(InvoiceStatus from, InvoiceStatus to) {
        boolean allowed = switch (from) {
            case DRAFT -> to == InvoiceStatus.OPEN || to == InvoiceStatus.VOID;
            case OPEN -> to == InvoiceStatus.PAID || to == InvoiceStatus.VOID;
            case PAID -> to == InvoiceStatus.REFUNDED;
            case VOID, REFUNDED -> false;
        };
        if (!allowed) {
            throw new BillingException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot transition invoice from " + from + " to " + to + ".");
        }
        return to;
    }
}
