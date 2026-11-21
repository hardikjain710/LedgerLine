package com.hardik.billing.payment.processor;

import com.hardik.billing.common.BillingException;
import com.hardik.billing.common.ErrorCode;

public class ProcessorUnavailableException extends BillingException {

    public ProcessorUnavailableException() {
        super(ErrorCode.PROCESSOR_UNAVAILABLE, "The payment processor is temporarily unavailable.");
    }
}
