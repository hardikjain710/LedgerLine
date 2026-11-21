package com.hardik.billing.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hardik.billing.common.BillingException;
import com.hardik.billing.common.ErrorCode;
import com.hardik.billing.common.GlobalExceptionHandler;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

@Service
public class IdempotencyService {

    private static final String KEY_SCOPE_CONSTRAINT = "idempotency_keys_idempotency_key_scope_key";

    private final IdempotencyRepository records;
    private final IdempotencyTransactions transactions;
    private final GlobalExceptionHandler errors;
    private final ObjectMapper json;

    public IdempotencyService(IdempotencyRepository records, IdempotencyTransactions transactions,
                              GlobalExceptionHandler errors, ObjectMapper json) {
        this.records = records;
        this.transactions = transactions;
        this.errors = errors;
        this.json = json;
    }

    public ResponseEntity<byte[]> execute(String key, String scope, byte[] rawBody,
                                          HttpStatus successStatus, Supplier<?> operation) {
        validateKey(key);
        String hash = sha256(rawBody);

        while (true) {
            var existing = records.findByIdempotencyKeyAndScope(key, scope);
            if (existing.isPresent()) {
                return reuse(existing.get(), hash);
            }

            Long reservation = tryReserve(key, scope, hash);
            if (reservation != null) {
                return executeReserved(reservation, successStatus, operation);
            }
            // The losing transaction has rolled back. Read the winning row in a fresh query.
        }
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BillingException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "Idempotency-Key is required.");
        }
        if (key.length() < 8 || key.length() > 255) {
            throw new BillingException(ErrorCode.VALIDATION_ERROR, "Idempotency-Key must be 8 to 255 characters.");
        }
    }

    private Long tryReserve(String key, String scope, String hash) {
        try {
            return transactions.reserve(key, scope, hash);
        } catch (DataIntegrityViolationException exception) {
            if (exception.getCause() instanceof ConstraintViolationException violation
                    && KEY_SCOPE_CONSTRAINT.equals(violation.getConstraintName())) {
                return null;
            }
            throw exception;
        }
    }

    private ResponseEntity<byte[]> reuse(IdempotencyRecord record, String hash) {
        if (!record.getRequestHash().equals(hash)) {
            throw new BillingException(ErrorCode.IDEMPOTENCY_KEY_REUSE,
                    "This idempotency key was already used with a different request body.");
        }
        if (record.getStatus() == IdempotencyRecord.Status.IN_PROGRESS) {
            throw new BillingException(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                    "A request with this idempotency key is already in progress.");
        }
        return ResponseEntity.status(record.getResponseStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotent-Replayed", "true")
                .body(record.getResponseBody().getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<byte[]> executeReserved(long id, HttpStatus successStatus, Supplier<?> operation) {
        SerializedResponse result;
        try {
            result = invoke(successStatus, operation);
            if (result.cacheable()) {
                transactions.complete(id, result.status().value(), result.body());
            }
        } catch (RuntimeException exception) {
            try {
                transactions.discard(id);
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }

        if (!result.cacheable()) {
            transactions.discard(id);
        }
        return ResponseEntity.status(result.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.body().getBytes(StandardCharsets.UTF_8));
    }

    private SerializedResponse invoke(HttpStatus successStatus, Supplier<?> operation) {
        try {
            return serialize(successStatus, operation.get());
        } catch (RuntimeException exception) {
            var error = errors.handleServiceError(exception);
            return serialize(error.getStatusCode(), error.getBody());
        }
    }

    private SerializedResponse serialize(HttpStatusCode status, Object value) {
        try {
            return new SerializedResponse(status, json.writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize the idempotent response.", exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private record SerializedResponse(HttpStatusCode status, String body) {

        boolean cacheable() {
            return !status.is5xxServerError() && status.value() != HttpStatus.BAD_REQUEST.value();
        }
    }
}
