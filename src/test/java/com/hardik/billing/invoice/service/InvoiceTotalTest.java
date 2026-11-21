package com.hardik.billing.invoice.service;

import com.hardik.billing.common.BillingException;
import com.hardik.billing.common.ErrorCode;
import com.hardik.billing.invoice.dto.CreateInvoiceRequest.LineItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceTotalTest {

    @Test
    void sumsQuantityTimesUnitAmountIncludingFreeItems() {
        var items = List.of(new LineItem("Consulting", 2, 1200L),
                new LineItem("Setup", 3, 100L), new LineItem("Bonus", 1, 0L));
        assertThat(InvoiceService.calculateTotal(items)).isEqualTo(2700L);
    }

    @Test
    void acceptsTheLargestRepresentableTotal() {
        assertThat(InvoiceService.calculateTotal(List.of(new LineItem("Maximum", 1, Long.MAX_VALUE))))
                .isEqualTo(Long.MAX_VALUE);
    }

    static Stream<Arguments> overflowCases() {
        return Stream.of(
                Arguments.of("multiplication", List.of(new LineItem("Large item", 2, Long.MAX_VALUE))),
                Arguments.of("addition", List.of(new LineItem("Maximum", 1, Long.MAX_VALUE),
                        new LineItem("Extra", 1, 1L))));
    }

    @ParameterizedTest(name = "{0} overflow")
    @MethodSource("overflowCases")
    void mapsOverflowToValidationError(String operation, List<LineItem> items) {
        assertThatThrownBy(() -> InvoiceService.calculateTotal(items))
                .isInstanceOfSatisfying(BillingException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}
