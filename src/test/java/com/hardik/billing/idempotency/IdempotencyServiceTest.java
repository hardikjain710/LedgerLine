package com.hardik.billing.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hardik.billing.common.BillingException;
import com.hardik.billing.common.ErrorCode;
import com.hardik.billing.common.GlobalExceptionHandler;
import com.hardik.billing.payment.processor.ProcessorUnavailableException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class IdempotencyServiceTest {

    private static final String KEY = "test-key";
    private static final String SCOPE = "POST /api/v1/payments/123/capture";
    private static final byte[] EMPTY_BODY = new byte[0];
    private static final String EMPTY_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final long RESERVATION = 42L;
    private static final String CREATED_BODY = "{\"status\":\"CREATED\"}";

    private final IdempotencyRepository records = mock(IdempotencyRepository.class);
    private final IdempotencyTransactions transactions = mock(IdempotencyTransactions.class);
    private final IdempotencyService service =
            new IdempotencyService(records, transactions, new GlobalExceptionHandler(), new ObjectMapper());

    @Test
    void reservesNewKeyAndStoresTheExactResponse() {
        stubNewKey();
        var response = service.execute(KEY, SCOPE, EMPTY_BODY, HttpStatus.CREATED, () -> Map.of("status", "CREATED"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(response.getBody()).isEqualTo(CREATED_BODY.getBytes(UTF_8));
        verify(transactions).reserve(KEY, SCOPE, EMPTY_HASH);
        verify(transactions).complete(RESERVATION, 201, CREATED_BODY);
    }

    @Test
    void replaysCompletedResponseWithoutExecutingBusinessLogic() {
        when(records.findByIdempotencyKeyAndScope(KEY, SCOPE)).thenReturn(Optional.of(completedRecord()));
        assertReplay();
        verifyNoInteractions(transactions);
    }

    @Test
    void rejectsDifferentHashBeforeCheckingStatus() {
        when(records.findByIdempotencyKeyAndScope(KEY, SCOPE))
                .thenReturn(Optional.of(new IdempotencyRecord(KEY, SCOPE, EMPTY_HASH)));
        assertThatThrownBy(() -> service.execute(KEY, SCOPE, "{}".getBytes(UTF_8), HttpStatus.OK,
                IdempotencyServiceTest::mustNotExecute))
                .isInstanceOfSatisfying(BillingException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSE));
        verifyNoInteractions(transactions);
    }

    @Test
    void rejectsSameHashWhileTheOriginalRequestIsInProgress() {
        when(records.findByIdempotencyKeyAndScope(KEY, SCOPE))
                .thenReturn(Optional.of(new IdempotencyRecord(KEY, SCOPE, EMPTY_HASH)));
        assertThatThrownBy(() -> service.execute(KEY, SCOPE, EMPTY_BODY, HttpStatus.OK,
                IdempotencyServiceTest::mustNotExecute))
                .isInstanceOfSatisfying(BillingException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.IDEMPOTENCY_IN_PROGRESS));
        verifyNoInteractions(transactions);
    }

    @Test
    void looksUpWinnerAfterTheUniqueConstraintRace() {
        var violation = mock(ConstraintViolationException.class);
        when(violation.getConstraintName()).thenReturn("idempotency_keys_idempotency_key_scope_key");
        when(records.findByIdempotencyKeyAndScope(KEY, SCOPE))
                .thenReturn(Optional.empty(), Optional.of(completedRecord()));
        when(transactions.reserve(KEY, SCOPE, EMPTY_HASH))
                .thenThrow(new DataIntegrityViolationException("Duplicate reservation", violation));

        assertReplay();
        verify(transactions).reserve(KEY, SCOPE, EMPTY_HASH);
    }

    @Test
    void discardsTheReservationForA5xxResponse() {
        stubNewKey();
        var response = service.execute(KEY, SCOPE, EMPTY_BODY, HttpStatus.OK, () -> {
            throw new ProcessorUnavailableException();
        });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(new String(response.getBody(), UTF_8)).contains("processor_unavailable");
        verify(transactions).discard(RESERVATION);
        verify(transactions).reserve(KEY, SCOPE, EMPTY_HASH);
        verifyNoMoreInteractions(transactions);
    }

    private void stubNewKey() {
        when(records.findByIdempotencyKeyAndScope(KEY, SCOPE)).thenReturn(Optional.empty());
        when(transactions.reserve(KEY, SCOPE, EMPTY_HASH)).thenReturn(RESERVATION);
    }

    private static IdempotencyRecord completedRecord() {
        var record = new IdempotencyRecord(KEY, SCOPE, EMPTY_HASH);
        record.complete(201, CREATED_BODY);
        return record;
    }

    private void assertReplay() {
        var response = service.execute(KEY, SCOPE, EMPTY_BODY, HttpStatus.OK,
                IdempotencyServiceTest::mustNotExecute);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(response.getBody()).isEqualTo(CREATED_BODY.getBytes(UTF_8));
    }

    private static Object mustNotExecute() {
        throw new AssertionError("Replay or rejection must never run the operation.");
    }
}
