package com.hardik.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.hardik.billing.payment.PaymentStatus;
import com.hardik.billing.payment.PaymentStatusHistory;
import com.hardik.billing.payment.dto.PaymentHistoryResponse;
import com.hardik.billing.payment.processor.ProcessorResult;
import com.hardik.billing.payment.processor.SimulatedProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "billing.processor=simulated")
class PaymentApiTest {

    private static final String API = "/api/v1";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private final TestRestTemplate http;

    @MockitoSpyBean
    private SimulatedProcessor processor;

    @Autowired
    PaymentApiTest(TestRestTemplate http) {
        this.http = http;
    }

    @Test
    void createsAuthorizesCapturesAndRefundsWithOrderedHistory() {
        String invoice = openInvoice();
        var created = createPayment(invoice, "tok_success");
        String payment = paymentPath(created);
        assertThat(created.path("invoiceId").asText()).isEqualTo(invoice);
        assertThat(created.path("amount").asLong()).isEqualTo(250);
        assertThat(created.path("currency").asText()).isEqualTo("USD");
        assertThat(created.path("status").asText()).isEqualTo("CREATED");
        assertThat(created.path("processor").asText()).isEqualTo("SIMULATED");
        assertThat(created.path("processorReference").isNull()).isTrue();
        assertThat(created.path("failureReason").isNull()).isTrue();
        assertThat(created.has("paymentMethodToken")).isFalse();
        assertThat(Instant.parse(created.path("createdAt").asText())).isNotNull();
        assertThat(created.path("updatedAt")).isEqualTo(created.path("createdAt"));
        assertThat(get(payment)).isEqualTo(created);

        var authorized = action(payment, "authorize");
        assertThat(authorized.path("status").asText()).isEqualTo("AUTHORIZED");
        String reference = authorized.path("processorReference").asText();
        assertThat(reference).startsWith("sim_");

        var captured = action(payment, "capture");
        assertThat(captured.path("status").asText()).isEqualTo("CAPTURED");
        assertThat(get(invoicePath(invoice)).path("status").asText()).isEqualTo("PAID");
        var refunded = action(payment, "refund");
        assertThat(refunded.path("status").asText()).isEqualTo("REFUNDED");
        assertThat(refunded.path("processorReference").asText()).isEqualTo(reference);
        assertThat(get(invoicePath(invoice)).path("status").asText()).isEqualTo("REFUNDED");

        var history = history(payment);
        assertThat(history).extracting(PaymentHistoryResponse::fromStatus)
                .containsExactly(null, PaymentStatus.CREATED, PaymentStatus.AUTHORIZED, PaymentStatus.CAPTURED);
        assertThat(history).extracting(PaymentHistoryResponse::toStatus)
                .containsExactly(PaymentStatus.CREATED, PaymentStatus.AUTHORIZED, PaymentStatus.CAPTURED,
                        PaymentStatus.REFUNDED);
        assertThat(history).extracting(PaymentHistoryResponse::reason)
                .containsExactly("created", "authorized", "captured", "refunded");
        assertThat(history).extracting(PaymentHistoryResponse::id).isSorted().doesNotHaveDuplicates();
        assertThat(history).allSatisfy(row -> {
            assertThat(row.actor()).isEqualTo(PaymentStatusHistory.Actor.API);
            assertThat(row.createdAt()).isNotNull();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"tok_decline", "tok_unknown"})
    void recordsDeclinesAndLetsTheOpenInvoiceBeRetried(String token) {
        String invoice = openInvoice();
        String payment = paymentPath(createPayment(invoice, token));
        var declined = action(payment, "authorize");
        String reason = token.equals("tok_decline") ? "card_declined" : "invalid_payment_method";

        assertThat(declined.path("status").asText()).isEqualTo("FAILED");
        assertThat(declined.path("processorReference").isNull()).isTrue();
        assertThat(declined.path("failureReason").asText()).isEqualTo(reason);
        assertThat(history(payment)).extracting(PaymentHistoryResponse::reason)
                .containsExactly("created", "authorization declined: " + reason);
        assertThat(get(invoicePath(invoice)).path("status").asText()).isEqualTo("OPEN");
        assertThat(createPayment(invoice, "tok_success").path("status").asText()).isEqualTo("CREATED");
    }

    @Test
    void voidsCreatedAndAuthorizedPaymentsAndThenAllowsInvoiceVoid() {
        String invoice = openInvoice();
        String created = paymentPath(createPayment(invoice, "tok_success"));
        assertThat(action(created, "void").path("status").asText()).isEqualTo("VOIDED");
        assertThat(history(created)).extracting(PaymentHistoryResponse::reason).containsExactly("created", "voided");
        verifyNoInteractions(processor);

        var retry = createPayment(invoice, "tok_success");
        String authorized = paymentPath(retry);
        String reference = action(authorized, "authorize").path("processorReference").asText();
        assertThat(action(authorized, "void").path("status").asText()).isEqualTo("VOIDED");
        verify(processor).voidAuthorization(reference, retry.path("id").asText());
        assertThat(history(authorized)).extracting(PaymentHistoryResponse::reason)
                .containsExactly("created", "authorized", "voided");
        assertThat(action(invoicePath(invoice), "void").path("status").asText()).isEqualTo("VOID");
    }

    @Test
    void unavailableProcessorLeavesCreatedPaymentAndHistoryUnchanged() {
        String invoice = openInvoice();
        var created = createPayment(invoice, "tok_error");
        String payment = paymentPath(created);

        assertError(post(payment + "/authorize", null), HttpStatus.SERVICE_UNAVAILABLE, "processor_unavailable");
        assertThat(get(payment)).isEqualTo(created);
        assertThat(history(payment)).extracting(PaymentHistoryResponse::reason).containsExactly("created");
    }

    @Test
    void enforcesTheActivePaymentIndexAndTheInvoiceVoidGuard() {
        String invoice = openInvoice();
        String payment = paymentPath(createPayment(invoice, "tok_success"));

        assertError(post(invoicePath(invoice) + "/payments", Map.of("paymentMethodToken", "tok_success")),
                HttpStatus.CONFLICT, "payment_already_active");
        assertError(post(invoicePath(invoice) + "/void", null), HttpStatus.CONFLICT, "invalid_state_transition");

        action(payment, "authorize");
        assertError(post(invoicePath(invoice) + "/void", null), HttpStatus.CONFLICT, "invalid_state_transition");
        assertThat(get(invoicePath(invoice)).path("status").asText()).isEqualTo("OPEN");
    }

    @Test
    void rejectsInvalidActionsWithoutContactingProcessor() {
        String payment = paymentPath(createPayment(openInvoice(), "tok_success"));
        for (String action : List.of("capture", "refund")) {
            assertError(post(payment + "/" + action, null), HttpStatus.CONFLICT, "invalid_state_transition");
        }
        verifyNoInteractions(processor);

        action(payment, "authorize");
        clearInvocations(processor);
        assertError(post(payment + "/authorize", null), HttpStatus.CONFLICT, "invalid_state_transition");
        verifyNoInteractions(processor);
        assertThat(history(payment)).extracting(PaymentHistoryResponse::reason).containsExactly("created", "authorized");
    }

    @Test
    void processorFailureRunsOutsideTransactionAndLeavesPaymentAndInvoiceUnchanged() {
        String invoice = openInvoice();
        String payment = paymentPath(createPayment(invoice, "tok_success"));
        var authorized = action(payment, "authorize");

        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return ProcessorResult.declined("invalid_reference");
        }).when(processor).capture(anyString(), anyLong(), anyString());

        var response = post(payment + "/capture", null);
        assertError(response, HttpStatus.BAD_GATEWAY, "processor_error");
        assertThat(response.getBody().path("error").path("message").asText()).contains("invalid_reference");
        assertThat(get(payment)).isEqualTo(authorized);
        assertThat(get(invoicePath(invoice)).path("status").asText()).isEqualTo("OPEN");
        assertThat(history(payment)).extracting(PaymentHistoryResponse::reason).containsExactly("created", "authorized");
    }

    @Test
    void validatesRequestsAndReportsUnknownIds() {
        String draft = draftInvoice();
        assertError(post(invoicePath(draft) + "/payments", Map.of("paymentMethodToken", "tok_success")),
                HttpStatus.CONFLICT, "invalid_state_transition");
        String invoice = openInvoice();
        assertError(post(invoicePath(invoice) + "/payments", Map.of("paymentMethodToken", " ")),
                HttpStatus.BAD_REQUEST, "validation_error");
        assertError(post(invoicePath(invoice) + "/payments", Map.of()), HttpStatus.BAD_REQUEST, "validation_error");
        assertError(http.getForEntity(API + "/payments/not-a-uuid", JsonNode.class),
                HttpStatus.BAD_REQUEST, "validation_error");

        UUID unknown = UUID.randomUUID();
        assertError(post(invoicePath(unknown.toString()) + "/payments", Map.of("paymentMethodToken", "tok_success")),
                HttpStatus.NOT_FOUND, "not_found");
        String payment = API + "/payments/" + unknown;
        assertError(http.getForEntity(payment, JsonNode.class), HttpStatus.NOT_FOUND, "not_found");
        assertError(http.getForEntity(payment + "/history", JsonNode.class), HttpStatus.NOT_FOUND, "not_found");
        assertError(post(payment + "/authorize", null), HttpStatus.NOT_FOUND, "not_found");
        verifyNoInteractions(processor);
    }

    private String openInvoice() {
        String id = draftInvoice();
        assertThat(action(invoicePath(id), "finalize").path("status").asText()).isEqualTo("OPEN");
        return id;
    }

    private String draftInvoice() {
        var customer = body(post(API + "/customers",
                Map.of("name", "Payment Test Customer", "email", UUID.randomUUID() + "@example.com")),
                HttpStatus.CREATED);
        return body(post(API + "/invoices", Map.of("customerId", customer.path("id").asText(), "currency", "USD",
                "lineItems", List.of(Map.of("description", "Item", "quantity", 2, "unitAmount", 125L)))),
                HttpStatus.CREATED).path("id").asText();
    }

    private JsonNode createPayment(String invoice, String token) {
        return body(post(invoicePath(invoice) + "/payments", Map.of("paymentMethodToken", token)), HttpStatus.CREATED);
    }

    private JsonNode action(String path, String action) {
        return body(post(path + "/" + action, null), HttpStatus.OK);
    }

    private JsonNode get(String path) {
        return body(http.getForEntity(path, JsonNode.class), HttpStatus.OK);
    }

    private ResponseEntity<JsonNode> post(String path, Object request) {
        if (path.startsWith(API + "/payments/") || path.endsWith("/payments")) {
            var headers = new HttpHeaders();
            headers.set("Idempotency-Key", UUID.randomUUID().toString());
            return http.postForEntity(path, new HttpEntity<>(request, headers), JsonNode.class);
        }
        return http.postForEntity(path, request, JsonNode.class);
    }

    private List<PaymentHistoryResponse> history(String payment) {
        var response = http.getForEntity(payment + "/history", PaymentHistoryResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return Arrays.asList(response.getBody());
    }

    private static String invoicePath(String invoice) {
        return API + "/invoices/" + invoice;
    }

    private static String paymentPath(JsonNode payment) {
        return API + "/payments/" + payment.path("id").asText();
    }

    private static JsonNode body(ResponseEntity<JsonNode> response, HttpStatus status) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private static void assertError(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(body(response, status).path("error").path("code").asText()).isEqualTo(code);
    }
}
