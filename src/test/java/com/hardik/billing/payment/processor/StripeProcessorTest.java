package com.hardik.billing.payment.processor;

import com.hardik.billing.payment.processor.StripeGateway.Failure;
import com.hardik.billing.payment.processor.StripeGateway.FailureType;
import com.hardik.billing.payment.processor.StripeGateway.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StripeProcessorTest {

    private static final String PAYMENT_ID = "platform-payment";
    private static final String REFERENCE = "pi_test";
    private static final String TOKEN = "pm_card_visa";
    private static final long AMOUNT = 1500;
    private static final Map<String, String> METADATA = Map.of("paymentId", PAYMENT_ID);

    private final StripeGateway gateway = mock(StripeGateway.class);
    private final StripeProcessor processor = new StripeProcessor(gateway);

    @Test
    void authorizesWithLowercaseCurrencyPaymentMetadataAndAnActionSpecificKey() {
        when(gateway.authorize(TOKEN, AMOUNT, "usd", METADATA, PAYMENT_ID + ":authorize"))
                .thenReturn(new Outcome(REFERENCE, "requires_capture"));

        assertThat(processor.authorize(TOKEN, AMOUNT, "USD", PAYMENT_ID))
                .isEqualTo(ProcessorResult.succeeded(REFERENCE));
    }

    @Test
    void returnsCardDeclineCodeWithNoReference() {
        when(gateway.authorize(TOKEN, AMOUNT, "usd", METADATA, PAYMENT_ID + ":authorize"))
                .thenThrow(new Failure(FailureType.CARD, "insufficient_funds"));

        assertThat(processor.authorize(TOKEN, AMOUNT, "USD", PAYMENT_ID))
                .isEqualTo(ProcessorResult.declined("insufficient_funds"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"requires_action", "processing", "succeeded"})
    void treatsUnexpectedAuthorizationStatusesAsUnavailable(String status) {
        when(gateway.authorize(TOKEN, AMOUNT, "usd", METADATA, PAYMENT_ID + ":authorize"))
                .thenReturn(new Outcome(REFERENCE, status));

        assertThatThrownBy(() -> processor.authorize(TOKEN, AMOUNT, "USD", PAYMENT_ID))
                .isInstanceOf(ProcessorUnavailableException.class);
    }

    @Test
    void treatsConnectionFailuresAsUnavailable() {
        when(gateway.authorize(TOKEN, AMOUNT, "usd", METADATA, PAYMENT_ID + ":authorize"))
                .thenThrow(new Failure(FailureType.TEMPORARY, "stripe_unavailable"));
        when(gateway.capture(REFERENCE, AMOUNT, PAYMENT_ID + ":capture"))
                .thenThrow(new Failure(FailureType.TEMPORARY, "stripe_unavailable"));
        when(gateway.voidAuthorization(REFERENCE, PAYMENT_ID + ":void"))
                .thenThrow(new Failure(FailureType.TEMPORARY, "stripe_unavailable"));
        when(gateway.refund(REFERENCE, AMOUNT, PAYMENT_ID + ":refund"))
                .thenThrow(new Failure(FailureType.TEMPORARY, "stripe_unavailable"));

        assertThatThrownBy(() -> processor.authorize(TOKEN, AMOUNT, "USD", PAYMENT_ID))
                .isInstanceOf(ProcessorUnavailableException.class);
        assertThatThrownBy(() -> processor.capture(REFERENCE, AMOUNT, PAYMENT_ID))
                .isInstanceOf(ProcessorUnavailableException.class);
        assertThatThrownBy(() -> processor.voidAuthorization(REFERENCE, PAYMENT_ID))
                .isInstanceOf(ProcessorUnavailableException.class);
        assertThatThrownBy(() -> processor.refund(REFERENCE, AMOUNT, PAYMENT_ID))
                .isInstanceOf(ProcessorUnavailableException.class);
    }

    @Test
    void usesStableDistinctKeysForCaptureVoidAndRefund() {
        when(gateway.capture(REFERENCE, AMOUNT, PAYMENT_ID + ":capture"))
                .thenReturn(new Outcome(REFERENCE, "succeeded"));
        when(gateway.voidAuthorization(REFERENCE, PAYMENT_ID + ":void"))
                .thenReturn(new Outcome(REFERENCE, "canceled"));
        when(gateway.refund(REFERENCE, AMOUNT, PAYMENT_ID + ":refund"))
                .thenReturn(new Outcome(REFERENCE, "succeeded"));

        assertThat(processor.capture(REFERENCE, AMOUNT, PAYMENT_ID)).isEqualTo(ProcessorResult.succeeded(REFERENCE));
        assertThat(processor.voidAuthorization(REFERENCE, PAYMENT_ID)).isEqualTo(ProcessorResult.succeeded(REFERENCE));
        assertThat(processor.refund(REFERENCE, AMOUNT, PAYMENT_ID)).isEqualTo(ProcessorResult.succeeded(REFERENCE));
    }

    @Test
    void returnsNonTransientActionFailuresWithTheirReason() {
        when(gateway.capture(REFERENCE, AMOUNT, PAYMENT_ID + ":capture"))
                .thenThrow(new Failure(FailureType.CARD, "card_declined"));
        when(gateway.voidAuthorization(REFERENCE, PAYMENT_ID + ":void"))
                .thenThrow(new Failure(FailureType.REJECTED, "payment_intent_unexpected_state"));
        when(gateway.refund(REFERENCE, AMOUNT, PAYMENT_ID + ":refund"))
                .thenThrow(new Failure(FailureType.REJECTED, "charge_already_refunded"));

        assertThat(processor.capture(REFERENCE, AMOUNT, PAYMENT_ID))
                .isEqualTo(ProcessorResult.declined("card_declined"));
        assertThat(processor.voidAuthorization(REFERENCE, PAYMENT_ID))
                .isEqualTo(ProcessorResult.declined("payment_intent_unexpected_state"));
        assertThat(processor.refund(REFERENCE, AMOUNT, PAYMENT_ID))
                .isEqualTo(ProcessorResult.declined("charge_already_refunded"));
    }

    @Test
    void treatsPendingActionStatusesAsUnavailable() {
        when(gateway.capture(REFERENCE, AMOUNT, PAYMENT_ID + ":capture"))
                .thenReturn(new Outcome(REFERENCE, "processing"));
        when(gateway.voidAuthorization(REFERENCE, PAYMENT_ID + ":void"))
                .thenReturn(new Outcome(REFERENCE, "requires_capture"));
        when(gateway.refund(REFERENCE, AMOUNT, PAYMENT_ID + ":refund"))
                .thenReturn(new Outcome(REFERENCE, "pending"));

        assertThatThrownBy(() -> processor.capture(REFERENCE, AMOUNT, PAYMENT_ID))
                .isInstanceOf(ProcessorUnavailableException.class);
        assertThatThrownBy(() -> processor.voidAuthorization(REFERENCE, PAYMENT_ID))
                .isInstanceOf(ProcessorUnavailableException.class);
        assertThatThrownBy(() -> processor.refund(REFERENCE, AMOUNT, PAYMENT_ID))
                .isInstanceOf(ProcessorUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "canceled"})
    void returnsTerminalRefundFailure(String status) {
        when(gateway.refund(REFERENCE, AMOUNT, PAYMENT_ID + ":refund"))
                .thenReturn(new Outcome(REFERENCE, status));

        assertThat(processor.refund(REFERENCE, AMOUNT, PAYMENT_ID))
                .isEqualTo(ProcessorResult.declined("refund_" + status));
    }
}
