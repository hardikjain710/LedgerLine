package com.hardik.billing.customer.service;

import com.hardik.billing.common.BillingException;
import com.hardik.billing.common.ErrorCode;
import com.hardik.billing.common.PageResponse;
import com.hardik.billing.customer.Customer;
import com.hardik.billing.customer.CustomerRepository;
import com.hardik.billing.customer.dto.CreateCustomerRequest;
import com.hardik.billing.customer.dto.CustomerResponse;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class CustomerService {

    private final CustomerRepository customers;

    public CustomerService(CustomerRepository customers) {
        this.customers = customers;
    }

    @Transactional
    public CustomerResponse create(CreateCustomerRequest request) {
        var customer = new Customer(request.name(), request.email(), Instant.now().truncatedTo(ChronoUnit.MICROS));
        try {
            // Flush here so the unique constraint is checked before leaving this method.
            return toResponse(customers.saveAndFlush(customer));
        } catch (DataIntegrityViolationException exception) {
            if (exception.getCause() instanceof ConstraintViolationException violation
                    && "customers_email_key".equals(violation.getConstraintName())) {
                throw new BillingException(ErrorCode.DUPLICATE_EMAIL, "A customer with this email already exists.");
            }
            throw exception;
        }
    }

    public CustomerResponse get(UUID id) {
        return customers.findById(id).map(CustomerService::toResponse)
                .orElseThrow(() -> new BillingException(ErrorCode.NOT_FOUND, "Customer was not found."));
    }

    public PageResponse<CustomerResponse> list(int page, int size) {
        return PageResponse.from(customers.findAll(PageResponse.pageRequest(page, size))
                .map(CustomerService::toResponse));
    }

    private static CustomerResponse toResponse(Customer customer) {
        return new CustomerResponse(customer.getId(), customer.getName(), customer.getEmail(), customer.getCreatedAt());
    }
}
