package com.hardik.billing.payment.processor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "billing.processor", havingValue = "simulated", matchIfMissing = true)
public class SimulatedProcessor implements PaymentProcessor {

    @Override
    public ProcessorResult authorize(String paymentMethodToken, long amount, String currency, String paymentId) {
        return switch (paymentMethodToken) {
            case "tok_success" -> ProcessorResult.succeeded("sim_" + UUID.randomUUID());
            case "tok_decline" -> ProcessorResult.declined("card_declined");
            case "tok_error" -> throw new ProcessorUnavailableException();
            case null, default -> ProcessorResult.declined("invalid_payment_method");
        };
    }

    @Override
    public ProcessorResult capture(String processorReference, long amount, String paymentId) {
        return checkReference(processorReference);
    }

    @Override
    public ProcessorResult voidAuthorization(String processorReference, String paymentId) {
        return checkReference(processorReference);
    }

    @Override
    public ProcessorResult refund(String processorReference, long amount, String paymentId) {
        return checkReference(processorReference);
    }

    private ProcessorResult checkReference(String reference) {
        if (reference != null && reference.startsWith("sim_")) {
            return ProcessorResult.succeeded(reference);
        }
        return ProcessorResult.declined("invalid_reference");
    }
}
