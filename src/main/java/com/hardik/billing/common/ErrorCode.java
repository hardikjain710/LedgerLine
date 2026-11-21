package com.hardik.billing.common;

import org.springframework.http.HttpStatus;

import java.util.Locale;

public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT),
    PAYMENT_ALREADY_ACTIVE(HttpStatus.CONFLICT),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT),
    IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT),
    IDEMPOTENCY_KEY_REUSE(HttpStatus.UNPROCESSABLE_ENTITY),
    PROCESSOR_ERROR(HttpStatus.BAD_GATEWAY),
    PROCESSOR_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
