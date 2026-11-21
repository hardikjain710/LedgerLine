package com.hardik.billing;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "billing.processor=simulated")
class PaymentConcurrencyApiTest {

    private static final String API = "/api/v1";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private final TestRestTemplate http;
    private final JdbcTemplate jdbc;

    @Autowired
    PaymentConcurrencyApiTest(TestRestTemplate http, JdbcTemplate jdbc) {
        this.http = http;
        this.jdbc = jdbc;
    }

    @RepeatedTest(3)
    void differentKeysCaptureTheSamePaymentExactlyOnce() throws Exception {
        UUID invoice = openInvoice();
        UUID payment = authorizePayment(invoice);

        var results = race(() -> action(payment, "capture"), () -> action(payment, "capture"));

        assertOneTransition(results, "CAPTURED");
        assertThat(state(paymentPath(payment))).isEqualTo("CAPTURED");
        assertThat(state(invoicePath(invoice))).isEqualTo("PAID");
        assertHistoryAndPosting(payment, "CAPTURED", "PAYMENT_CAPTURED");
        assertReconciles();
    }

    @RepeatedTest(3)
    void differentKeysRefundTheSamePaymentExactlyOnce() throws Exception {
        UUID invoice = openInvoice();
        UUID payment = authorizePayment(invoice);
        assertThat(body(action(payment, "capture"), HttpStatus.OK).path("status").asText()).isEqualTo("CAPTURED");

        var results = race(() -> action(payment, "refund"), () -> action(payment, "refund"));

        assertOneTransition(results, "REFUNDED");
        assertThat(state(paymentPath(payment))).isEqualTo("REFUNDED");
        assertThat(state(invoicePath(invoice))).isEqualTo("REFUNDED");
        assertHistoryAndPosting(payment, "REFUNDED", "PAYMENT_REFUNDED");
        assertReconciles();
    }

    @RepeatedTest(3)
    void differentKeysCreateOnlyOneActivePaymentForTheInvoice() throws Exception {
        UUID invoice = openInvoice();

        var results = race(() -> createPayment(invoice), () -> createPayment(invoice));

        var created = body(responseWithStatus(results, HttpStatus.CREATED), HttpStatus.CREATED);
        assertThat(created.path("status").asText()).isEqualTo("CREATED");
        assertThat(errorCode(responseWithStatus(results, HttpStatus.CONFLICT)))
                .isEqualTo("payment_already_active");
        assertThat(activePayments(invoice)).isEqualTo(1L);
        assertThat(state(invoicePath(invoice))).isEqualTo("OPEN");
        assertReconciles();
    }

    @RepeatedTest(5)
    void concurrentCreationAndInvoiceVoidLeaveAConsistentState() throws Exception {
        UUID invoice = openInvoice();

        var results = race(() -> createPayment(invoice),
                () -> http.postForEntity(invoicePath(invoice) + "/void", null, JsonNode.class));
        var create = results.get(0);
        var voidInvoice = results.get(1);

        if (create.getStatusCode().equals(HttpStatus.CREATED)) {
            assertThat(body(create, HttpStatus.CREATED).path("status").asText()).isEqualTo("CREATED");
            assertThat(errorCode(voidInvoice)).isEqualTo("invalid_state_transition");
            assertThat(state(invoicePath(invoice))).isEqualTo("OPEN");
            assertThat(activePayments(invoice)).isEqualTo(1L);
        } else {
            assertThat(errorCode(create)).isEqualTo("invalid_state_transition");
            assertThat(body(voidInvoice, HttpStatus.OK).path("status").asText()).isEqualTo("VOID");
            assertThat(state(invoicePath(invoice))).isEqualTo("VOID");
            assertThat(activePayments(invoice)).isZero();
        }
        assertReconciles();
    }

    private static List<ResponseEntity<JsonNode>> race(Supplier<ResponseEntity<JsonNode>> first,
                                                       Supplier<ResponseEntity<JsonNode>> second) throws Exception {
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return first.get();
            });
            var b = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return second.get();
            });
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private static void assertOneTransition(List<ResponseEntity<JsonNode>> responses, String expectedStatus) {
        var winner = body(responseWithStatus(responses, HttpStatus.OK), HttpStatus.OK);
        assertThat(winner.path("status").asText()).isEqualTo(expectedStatus);
        assertThat(errorCode(responseWithStatus(responses, HttpStatus.CONFLICT)))
                .isIn("invalid_state_transition", "concurrent_modification");
    }

    private static ResponseEntity<JsonNode> responseWithStatus(List<ResponseEntity<JsonNode>> responses,
                                                               HttpStatus status) {
        assertThat(responses).filteredOn(response -> response.getStatusCode().equals(status)).hasSize(1);
        return responses.stream().filter(response -> response.getStatusCode().equals(status)).findFirst().orElseThrow();
    }

    private void assertHistoryAndPosting(UUID payment, String status, String kind) {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM payment_status_history WHERE payment_id = ? AND to_status = ?
                """, Long.class, payment, status)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM ledger_transactions WHERE reference_id = ? AND kind = ?
                """, Long.class, payment, kind)).isEqualTo(1L);
    }

    private long activePayments(UUID invoice) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM payments WHERE invoice_id = ?
                AND status IN ('CREATED', 'AUTHORIZED', 'CAPTURED')
                """, Long.class, invoice);
    }

    private void assertReconciles() {
        var report = body(http.postForEntity(API + "/admin/reconciliation/run", null, JsonNode.class), HttpStatus.OK);
        assertThat(report.path("status").asText()).isEqualTo("OK");
        assertThat(report.path("details").isArray()).isTrue();
        assertThat(report.path("details")).isEmpty();
    }

    private UUID openInvoice() {
        var customer = body(http.postForEntity(API + "/customers",
                Map.of("name", "Concurrency Test Customer", "email", UUID.randomUUID() + "@example.com"),
                JsonNode.class), HttpStatus.CREATED);
        var invoice = body(http.postForEntity(API + "/invoices",
                Map.of("customerId", customer.path("id").asText(), "currency", "USD",
                        "lineItems", List.of(Map.of("description", "Test item", "quantity", 1, "unitAmount", 250L))),
                JsonNode.class), HttpStatus.CREATED);
        UUID invoiceId = UUID.fromString(invoice.path("id").asText());
        var opened = body(http.postForEntity(invoicePath(invoiceId) + "/finalize", null, JsonNode.class), HttpStatus.OK);
        assertThat(opened.path("status").asText()).isEqualTo("OPEN");
        return invoiceId;
    }

    private UUID authorizePayment(UUID invoice) {
        var created = body(createPayment(invoice), HttpStatus.CREATED);
        UUID payment = UUID.fromString(created.path("id").asText());
        assertThat(body(action(payment, "authorize"), HttpStatus.OK).path("status").asText()).isEqualTo("AUTHORIZED");
        return payment;
    }

    private ResponseEntity<JsonNode> createPayment(UUID invoice) {
        return paymentPost(invoicePath(invoice) + "/payments", Map.of("paymentMethodToken", "tok_success"));
    }

    private ResponseEntity<JsonNode> action(UUID payment, String action) {
        return paymentPost(paymentPath(payment) + "/" + action, null);
    }

    private ResponseEntity<JsonNode> paymentPost(String path, Object request) {
        var headers = new HttpHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return http.postForEntity(path, new HttpEntity<>(request, headers), JsonNode.class);
    }

    private String state(String path) {
        return body(http.getForEntity(path, JsonNode.class), HttpStatus.OK).path("status").asText();
    }

    private static String invoicePath(UUID id) {
        return API + "/invoices/" + id;
    }

    private static String paymentPath(UUID id) {
        return API + "/payments/" + id;
    }

    private static String errorCode(ResponseEntity<JsonNode> response) {
        return body(response, HttpStatus.CONFLICT).path("error").path("code").asText();
    }

    private static JsonNode body(ResponseEntity<JsonNode> response, HttpStatus status) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }
}
