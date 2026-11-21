package com.hardik.billing.payment.processor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

@Component
@ConditionalOnProperty(name = "billing.processor", havingValue = "stripe")
public class StripeProcessor implements PaymentProcessor {

    private final StripeGateway gateway;

    public StripeProcessor(StripeGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public ProcessorResult authorize(String paymentMethodToken, long amount, String currency, String paymentId) {
        try {
            var outcome = gateway.authorize(paymentMethodToken, amount, currency.toLowerCase(Locale.ROOT),
                    Map.of("paymentId", paymentId), paymentId + ":authorize");
            return requireStatus(outcome, "requires_capture");
        } catch (StripeGateway.Failure failure) {
            if (failure.type() == StripeGateway.FailureType.CARD) {
                return ProcessorResult.declined(failure.getMessage());
            }
            throw new ProcessorUnavailableException();
        }
    }

    @Override
    public ProcessorResult capture(String processorReference, long amount, String paymentId) {
        return complete("succeeded", () -> gateway.capture(processorReference, amount, paymentId + ":capture"));
    }

    @Override
    public ProcessorResult voidAuthorization(String processorReference, String paymentId) {
        return complete("canceled", () -> gateway.voidAuthorization(processorReference, paymentId + ":void"));
    }

    @Override
    public ProcessorResult refund(String processorReference, long amount, String paymentId) {
        return complete("succeeded", () -> {
            var outcome = gateway.refund(processorReference, amount, paymentId + ":refund");
            if ("failed".equals(outcome.status()) || "canceled".equals(outcome.status())) {
                throw new StripeGateway.Failure(StripeGateway.FailureType.REJECTED, "refund_" + outcome.status());
            }
            return outcome;
        });
    }

    private static ProcessorResult complete(String status, Supplier<StripeGateway.Outcome> call) {
        try {
            return requireStatus(call.get(), status);
        } catch (StripeGateway.Failure failure) {
            if (failure.type() == StripeGateway.FailureType.TEMPORARY) {
                throw new ProcessorUnavailableException();
            }
            return ProcessorResult.declined(failure.getMessage());
        }
    }

    private static ProcessorResult requireStatus(StripeGateway.Outcome outcome, String expected) {
        if (!expected.equals(outcome.status()) || outcome.reference() == null || outcome.reference().isBlank()) {
            throw new ProcessorUnavailableException();
        }
        return ProcessorResult.succeeded(outcome.reference());
    }
}
