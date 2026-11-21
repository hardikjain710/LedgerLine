package com.hardik.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hardik.billing.payment.processor.SimulatedProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "billing.processor=simulated")
class IdempotencyApiTest {

    private static final String API = "/api/v1";
    private static final String CREATE_BODY = "{\"paymentMethodToken\":\"tok_success\"}";
    private static final String EMPTY_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private final TestRestTemplate http;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    @MockitoSpyBean
    private SimulatedProcessor processor;

    @Autowired
    IdempotencyApiTest(TestRestTemplate http, JdbcTemplate jdbc, ObjectMapper json) {
        this.http = http;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Test
    void replaysCreateWithIdenticalStatusAndBodyAndOnlyOnePayment() {
        String invoice = openInvoice();
        String path = invoice + "/payments";
        String key = key();

        var first = post(path, key, CREATE_BODY);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(first.getBody()).contains("\"status\":\"CREATED\"");
        assertReplay(first, post(path, key, CREATE_BODY));

        UUID invoiceId = UUID.fromString(invoice.substring(invoice.lastIndexOf('/') + 1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE invoice_id = ?", Long.class, invoiceId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT response_body FROM idempotency_keys WHERE idempotency_key = ?",
                String.class, key)).isEqualTo(first.getBody());
    }

    @Test
    void replayedCaptureDoesNotCallProcessorAgain() throws Exception {
        String path = authorizedPayment() + "/capture";
        String key = key();
        var first = post(path, key, null);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).contains("\"status\":\"CAPTURED\"");
        assertReplay(first, post(path, key, null));
        verify(processor, times(1)).capture(anyString(), anyLong(), anyString());
    }

    @Test
    void rejectsSameKeyWhenRawBytesChangeEvenIfTheParsedBodyIsIdentical() {
        String path = openInvoice() + "/payments";
        String key = key();
        assertThat(post(path, key, CREATE_BODY).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertError(post(path, key, " " + CREATE_BODY + "\n"), HttpStatus.UNPROCESSABLE_ENTITY,
                "idempotency_key_reuse");
        assertError(post(path, key, "{\"paymentMethodToken\":\"tok_decline\"}"),
                HttpStatus.UNPROCESSABLE_ENTITY, "idempotency_key_reuse");
    }

    @Test
    void sameKeyCanBeUsedOnAnotherPath() throws Exception {
        String key = key();
        var created = post(openInvoice() + "/payments", key, CREATE_BODY);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var authorized = post(paymentPath(created) + "/authorize", key, null);

        assertThat(authorized.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(authorized.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(authorized.getBody()).contains("\"status\":\"AUTHORIZED\"");
        assertThat(reservations(key)).isEqualTo(2L);
    }

    @Test
    void storesAndReplaysBusinessErrorAfterThePaymentBecomesEligible() throws Exception {
        String payment = createPayment(CREATE_BODY);
        String key = key();
        String path = payment + "/capture";
        var first = post(path, key, null);
        assertError(first, HttpStatus.CONFLICT, "invalid_state_transition");
        assertThat(post(payment + "/authorize", key(), null).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertReplay(first, post(path, key, null));
        assertThat(http.getForObject(payment, JsonNode.class).path("status").asText()).isEqualTo("AUTHORIZED");
        verify(processor, times(0)).capture(anyString(), anyLong(), anyString());
    }

    @Test
    void unavailableProcessorClearsReservationAndSameKeyCallsItAgain() throws Exception {
        String payment = createPayment("{\"paymentMethodToken\":\"tok_error\"}");
        String key = key();
        for (int attempt = 0; attempt < 2; attempt++) {
            assertError(post(payment + "/authorize", key, null), HttpStatus.SERVICE_UNAVAILABLE,
                    "processor_unavailable");
            assertThat(reservations(key)).isZero();
        }
        verify(processor, times(2)).authorize(anyString(), anyLong(), anyString(), anyString());
        assertThat(http.getForObject(payment, JsonNode.class).path("status").asText()).isEqualTo("CREATED");
    }

    @Test
    void unexpectedProcessorErrorClearsReservationAndSameKeyCanSucceed() throws Exception {
        String path = authorizedPayment() + "/capture";
        String key = key();
        doThrow(new IllegalStateException("Simulated unexpected failure")).doCallRealMethod()
                .when(processor).capture(anyString(), anyLong(), anyString());

        assertError(post(path, key, null), HttpStatus.INTERNAL_SERVER_ERROR, "internal_error");
        assertThat(reservations(key)).isZero();
        var retried = post(path, key, null);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retried.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        verify(processor, times(2)).capture(anyString(), anyLong(), anyString());
    }

    @Test
    void validatesBodyBeforeCheckingExistingKeysAndCreatesNoRowForBadBody() {
        String path = openInvoice() + "/payments";
        String key = key();
        String blankToken = "{\"paymentMethodToken\":\" \"}";

        assertError(post(path, key, blankToken), HttpStatus.BAD_REQUEST, "validation_error");
        assertThat(reservations(key)).isZero();
        assertThat(post(path, key, CREATE_BODY).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertError(post(path, key, blankToken), HttpStatus.BAD_REQUEST, "validation_error");
        assertThat(reservations(key)).isEqualTo(1L);
    }

    @Test
    void rejectsMissingBlankShortAndLongKeysWithoutCreatingRows() {
        String path = openInvoice() + "/payments";

        assertError(post(path, null, CREATE_BODY), HttpStatus.BAD_REQUEST, "idempotency_key_required");
        assertError(post(path, " ", CREATE_BODY), HttpStatus.BAD_REQUEST, "idempotency_key_required");
        assertError(post(path, "1234567", CREATE_BODY), HttpStatus.BAD_REQUEST, "validation_error");
        assertError(post(path, "a".repeat(256), CREATE_BODY), HttpStatus.BAD_REQUEST, "validation_error");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE scope = ?",
                Long.class, "POST " + path)).isZero();
    }

    @Test
    void rejectsAnExistingInProgressReservationWithMatchingHash() throws Exception {
        String path = authorizedPayment() + "/capture";
        String key = key();
        jdbc.update("""
                INSERT INTO idempotency_keys (idempotency_key, scope, request_hash, status)
                VALUES (?, ?, ?, 'IN_PROGRESS')
                """, key, "POST " + path, EMPTY_HASH);

        assertError(post(path, key, null), HttpStatus.CONFLICT, "idempotency_in_progress");
        assertThat(reservations(key)).isEqualTo(1L);
        verify(processor, times(0)).capture(anyString(), anyLong(), anyString());
    }

    @Test
    void overlappingSameKeyCapturesRunTheProcessorAndBusinessLogicOnce() throws Exception {
        String payment = authorizedPayment();
        String path = payment + "/capture";
        String key = key();
        var processorEntered = new CountDownLatch(1);
        var releaseProcessor = new CountDownLatch(1);
        doAnswer(invocation -> {
            processorEntered.countDown();
            if (!releaseProcessor.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the competing request.");
            }
            return invocation.callRealMethod();
        }).when(processor).capture(anyString(), anyLong(), anyString());

        var threads = Executors.newFixedThreadPool(2);
        try {
            var first = threads.submit(() -> post(path, key, null));
            assertThat(processorEntered.await(10, TimeUnit.SECONDS)).isTrue();
            var second = threads.submit(() -> post(path, key, null));
            assertError(second.get(10, TimeUnit.SECONDS), HttpStatus.CONFLICT, "idempotency_in_progress");

            releaseProcessor.countDown();
            var completed = first.get(10, TimeUnit.SECONDS);
            assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(completed.getBody()).contains("\"status\":\"CAPTURED\"");
            assertReplay(completed, post(path, key, null));

            JsonNode history = http.getForObject(payment + "/history", JsonNode.class);
            assertThat(history).hasSize(3);
            assertThat(history.get(2).path("toStatus").asText()).isEqualTo("CAPTURED");
            verify(processor, times(1)).capture(anyString(), anyLong(), anyString());
        } finally {
            releaseProcessor.countDown();
            threads.shutdownNow();
        }
    }

    private String authorizedPayment() throws Exception {
        String payment = createPayment(CREATE_BODY);
        assertThat(post(payment + "/authorize", key(), null).getStatusCode()).isEqualTo(HttpStatus.OK);
        return payment;
    }

    private String createPayment(String body) throws Exception {
        var response = post(openInvoice() + "/payments", key(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return paymentPath(response);
    }

    private String paymentPath(ResponseEntity<String> created) throws Exception {
        return API + "/payments/" + json.readTree(created.getBody()).path("id").asText();
    }

    private String openInvoice() {
        var customer = http.postForEntity(API + "/customers",
                Map.of("name", "Idempotency Test Customer", "email", key() + "@example.com"), JsonNode.class);
        assertThat(customer.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var invoice = http.postForEntity(API + "/invoices",
                Map.of("customerId", customer.getBody().path("id").asText(), "currency", "USD",
                        "lineItems", List.of(Map.of("description", "Item", "quantity", 1, "unitAmount", 250L))),
                JsonNode.class);
        assertThat(invoice.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String path = API + "/invoices/" + invoice.getBody().path("id").asText();
        assertThat(http.postForEntity(path + "/finalize", null, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        return path;
    }

    private ResponseEntity<String> post(String path, String key, String body) {
        var headers = new HttpHeaders();
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return http.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    private long reservations(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?", Long.class, key);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static void assertReplay(ResponseEntity<String> original, ResponseEntity<String> replay) {
        assertThat(replay.getStatusCode()).isEqualTo(original.getStatusCode());
        assertThat(replay.getBody()).isEqualTo(original.getBody());
        assertThat(replay.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
    }

    private static void assertError(ResponseEntity<String> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).contains("\"code\":\"" + code + "\"");
        assertThat(response.getHeaders().getFirst("Idempotent-Replayed")).isNull();
    }
}
