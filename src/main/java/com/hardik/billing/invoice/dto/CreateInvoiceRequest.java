package com.hardik.billing.invoice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateInvoiceRequest(
        @NotNull UUID customerId,
        @NotNull @Pattern(regexp = "INR|USD") String currency,
        @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid LineItem> lineItems
) {
    public record LineItem(
            @NotBlank @Size(max = 500) String description,
            @NotNull @Min(1) @Max(10000) Integer quantity,
            @NotNull @Min(0) Long unitAmount
    ) {
    }
}
