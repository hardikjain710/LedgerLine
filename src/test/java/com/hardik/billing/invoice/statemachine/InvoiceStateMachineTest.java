package com.hardik.billing.invoice.statemachine;

import com.hardik.billing.common.BillingException;
import com.hardik.billing.common.ErrorCode;
import com.hardik.billing.invoice.InvoiceStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceStateMachineTest {

    @ParameterizedTest
    @CsvSource({
            "DRAFT, OPEN",
            "DRAFT, VOID",
            "OPEN, PAID",
            "OPEN, VOID",
            "PAID, REFUNDED"
    })
    void permitsValidTransitions(InvoiceStatus from, InvoiceStatus to) {
        assertThat(InvoiceStateMachine.transition(from, to)).isEqualTo(to);
    }

    @ParameterizedTest
    @CsvSource({
            "DRAFT, DRAFT", "DRAFT, PAID", "DRAFT, REFUNDED",
            "OPEN, DRAFT", "OPEN, OPEN", "OPEN, REFUNDED",
            "PAID, DRAFT", "PAID, OPEN", "PAID, PAID", "PAID, VOID",
            "VOID, DRAFT", "VOID, OPEN", "VOID, PAID", "VOID, VOID", "VOID, REFUNDED",
            "REFUNDED, DRAFT", "REFUNDED, OPEN", "REFUNDED, PAID", "REFUNDED, VOID", "REFUNDED, REFUNDED"
    })
    void rejectsInvalidTransitions(InvoiceStatus from, InvoiceStatus to) {
        assertThatThrownBy(() -> InvoiceStateMachine.transition(from, to))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }
}
