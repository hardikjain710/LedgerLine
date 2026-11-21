package com.hardik.billing.customer.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreateCustomerRequestTest {

    @Test
    void trimsAndLowercasesEmail() {
        var request = new CreateCustomerRequest("Example Customer", "  INFO@EXAMPLE.COM  ");

        assertThat(request.email()).isEqualTo("info@example.com");
    }

    @Test
    void leavesMissingEmailForBeanValidation() {
        assertThat(new CreateCustomerRequest("Example Customer", null).email()).isNull();
    }
}
