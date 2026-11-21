package com.hardik.billing.payment.processor;

public interface PaymentProcessor {
    ProcessorResult authorize(String paymentMethodToken, long amount, String currency, String paymentId);

    ProcessorResult capture(String processorReference, long amount, String paymentId);

    ProcessorResult voidAuthorization(String processorReference, String paymentId);

    ProcessorResult refund(String processorReference, long amount, String paymentId);
}
