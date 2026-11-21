package com.hardik.billing.payment.processor;

import com.hardik.billing.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatedProcessorTest {

    private static final String PAYMENT_ID = UUID.randomUUID().toString();
    private final SimulatedProcessor processor = new SimulatedProcessor();

    @Test
    void authorizesAndAcceptsTheGeneratedReferenceForLaterActions() {
        var result = processor.authorize("tok_success", 250L, "USD", PAYMENT_ID);

        assertThat(result.success()).isTrue();
        assertThat(result.failureReason()).isNull();
        String reference = result.processorReference();
        assertThat(reference).startsWith("sim_");
        assertThat(UUID.fromString(reference.substring("sim_".length())).version()).isEqualTo(4);
        assertThat(processor.capture(reference, 250L, PAYMENT_ID)).isEqualTo(result);
        assertThat(processor.voidAuthorization(reference, PAYMENT_ID)).isEqualTo(result);
        assertThat(processor.refund(reference, 250L, PAYMENT_ID)).isEqualTo(result);
    }

    @ParameterizedTest
    @CsvSource({"tok_decline, card_declined", "tok_unknown, invalid_payment_method"})
    void declinesKnownDeclineAndUnknownTokens(String token, String reason) {
        assertThat(processor.authorize(token, 250L, "USD", PAYMENT_ID))
                .isEqualTo(ProcessorResult.declined(reason));
    }

    @Test
    void throwsWhenProcessorIsUnavailable() {
        assertThatThrownBy(() -> processor.authorize("tok_error", 250L, "USD", PAYMENT_ID))
                .isInstanceOfSatisfying(ProcessorUnavailableException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.PROCESSOR_UNAVAILABLE));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"pi_invalid", "prefix_sim_reference"})
    void rejectsInvalidReferences(String reference) {
        var failure = ProcessorResult.declined("invalid_reference");
        assertThat(processor.capture(reference, 250L, PAYMENT_ID)).isEqualTo(failure);
        assertThat(processor.voidAuthorization(reference, PAYMENT_ID)).isEqualTo(failure);
        assertThat(processor.refund(reference, 250L, PAYMENT_ID)).isEqualTo(failure);
    }
}
