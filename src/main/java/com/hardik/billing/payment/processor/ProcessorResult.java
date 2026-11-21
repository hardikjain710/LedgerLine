package com.hardik.billing.payment.processor;

public record ProcessorResult(boolean success, String processorReference, String failureReason) {

    public static ProcessorResult succeeded(String processorReference) {
        return new ProcessorResult(true, processorReference, null);
    }

    public static ProcessorResult declined(String failureReason) {
        return new ProcessorResult(false, null, failureReason);
    }
}
