package com.hardik.billing.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePaymentRequest(@NotBlank @Size(max = 255) String paymentMethodToken) {
}
