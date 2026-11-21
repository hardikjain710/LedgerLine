package com.hardik.billing.payment.statemachine;

import com.hardik.billing.common.BillingException;
import com.hardik.billing.common.ErrorCode;
import com.hardik.billing.payment.PaymentStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentStateMachineTest {

    @ParameterizedTest
    @CsvSource({
            "CREATED, AUTHORIZED", "CREATED, FAILED", "CREATED, VOIDED",
            "AUTHORIZED, CAPTURED", "AUTHORIZED, VOIDED",
            "CAPTURED, REFUNDED"
    })
    void permitsValidTransitions(PaymentStatus from, PaymentStatus to) {
        assertThat(PaymentStateMachine.transition(from, to)).isEqualTo(to);
    }

    @ParameterizedTest
    @CsvSource({
            "CREATED, CREATED", "CREATED, CAPTURED", "CREATED, REFUNDED",
            "AUTHORIZED, CREATED", "AUTHORIZED, AUTHORIZED", "AUTHORIZED, REFUNDED", "AUTHORIZED, FAILED",
            "CAPTURED, CREATED", "CAPTURED, AUTHORIZED", "CAPTURED, CAPTURED", "CAPTURED, FAILED", "CAPTURED, VOIDED",
            "REFUNDED, CREATED", "REFUNDED, AUTHORIZED", "REFUNDED, CAPTURED",
            "REFUNDED, REFUNDED", "REFUNDED, FAILED", "REFUNDED, VOIDED",
            "FAILED, CREATED", "FAILED, AUTHORIZED", "FAILED, CAPTURED",
            "FAILED, REFUNDED", "FAILED, FAILED", "FAILED, VOIDED",
            "VOIDED, CREATED", "VOIDED, AUTHORIZED", "VOIDED, CAPTURED",
            "VOIDED, REFUNDED", "VOIDED, FAILED", "VOIDED, VOIDED"
    })
    void rejectsInvalidTransitions(PaymentStatus from, PaymentStatus to) {
        assertThatThrownBy(() -> PaymentStateMachine.transition(from, to))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }
}
