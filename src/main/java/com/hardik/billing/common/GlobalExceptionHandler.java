package com.hardik.billing.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleInvalidBody(MethodArgumentNotValidException exception) {
        var details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.FieldProblem(error.getField(), error.getDefaultMessage()))
                .toList();
        return response(ErrorCode.VALIDATION_ERROR, "Request validation failed.", details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadableBody() {
        return response(ErrorCode.VALIDATION_ERROR, "Request body is missing or invalid.", List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleInvalidParameter(MethodArgumentTypeMismatchException exception) {
        var detail = new ApiError.FieldProblem(exception.getName(), "has an invalid value");
        return response(ErrorCode.VALIDATION_ERROR, "Request validation failed.", List.of(detail));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException exception) {
        var detail = new ApiError.FieldProblem(exception.getParameterName(), "is required");
        return response(ErrorCode.VALIDATION_ERROR, "Request validation failed.", List.of(detail));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> handleParameterConstraints() {
        return response(ErrorCode.VALIDATION_ERROR, "Request parameter is out of range.", List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleUnknownResource() {
        return response(ErrorCode.NOT_FOUND, "Resource was not found.", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleServiceError(Exception exception) {
        if (exception instanceof BillingException businessError) {
            return response(businessError.code(), businessError.getMessage(), List.of());
        }
        if (exception instanceof ObjectOptimisticLockingFailureException) {
            return response(ErrorCode.CONCURRENT_MODIFICATION, "The resource was modified concurrently.", List.of());
        }
        log.error("Unexpected request failure ({})", exception.getClass().getName());
        return response(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", List.of());
    }

    private ResponseEntity<ApiError> response(ErrorCode code, String message, List<ApiError.FieldProblem> details) {
        return ResponseEntity.status(code.status()).body(new ApiError(new ApiError.Body(code.value(), message, details)));
    }
}
