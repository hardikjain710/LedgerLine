package com.hardik.billing.customer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public record CreateCustomerRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Email @Size(max = 320) String email
) {
    public CreateCustomerRequest {
        if (email != null) {
            email = email.trim().toLowerCase(Locale.ROOT);
        }
    }
}
