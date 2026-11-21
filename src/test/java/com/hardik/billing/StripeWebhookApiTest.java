package com.hardik.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hardik.billing.ledger.LedgerKind;
import com.hardik.billing.ledger.service.LedgerService;
import com.hardik.billing.payment.processor.SimulatedProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "billing.processor=simulated")
@ContextConfiguration(initializers = StripeWebhookApiTest.SigningSecretInitializer.class)
@Sql(statements = "TRUNCATE customers, ledger_transactions, webhook_events, idempotency_keys CASCADE")
class StripeWebhookApiTest {

    private static final String API = "/api/v1";
    private static final String AUTHORIZED = "payment_intent.amount_capturable_updated";
    private static final String CAPTURED = "payment_intent.succeeded";
    private static final String REFUNDED = "charge.refunded";
    private static final String FAILED = "payment_intent.payment_failed";
    private static final String VOIDED = "payment_intent.canceled";
    private static final String SIGNING_SECRET = UUID.randomUUID().toString();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private final TestRestTemplate http;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final LedgerService ledger;

    @MockitoSpyBean
    private SimulatedProcessor processor;

    @Autowired
    StripeWebhookApiTest(TestRestTemplate http, JdbcTemplate jdbc, ObjectMapper json, LedgerService ledger) {
        this.http = http;
        this.jdbc = jdbc;
        this.json = json;
        this.ledger = ledger;
    }

    @Test
    void signedWebhooksAloneAuthorizeCaptureAndRefund() {
        var flow = createPayment();
        String reference = reference();

        var authorized = intentEvent(AUTHORIZED, reference, flow.payment());
        receiveOk(authorized);
        var payment = get(flow.paymentPath());
        assertThat(payment.path("status").asText()).isEqualTo("AUTHORIZED");
        assertThat(payment.path("processorReference").asText()).isEqualTo(reference);
        assertHistory(flow, "AUTHORIZED", AUTHORIZED);

        // With no metadata, capture must find the same payment by its stored PaymentIntent ID.
        receiveOk(intentEvent(CAPTURED, reference, null));
        assertThat(state(flow.invoicePath())).isEqualTo("PAID");
        assertThat(state(flow.paymentPath())).isEqualTo("CAPTURED");
        assertHistory(flow, "CAPTURED", CAPTURED);
        assertPostingCount(flow, "PAYMENT_CAPTURED", 1);

        receiveOk(chargeRefunded(reference));
        assertThat(state(flow.invoicePath())).isEqualTo("REFUNDED");
        assertThat(state(flow.paymentPath())).isEqualTo("REFUNDED");
        assertHistory(flow, "REFUNDED", REFUNDED);
        assertPostingCount(flow, "PAYMENT_REFUNDED", 1);
        verifyNoInteractions(processor);
        assertReconciles();
    }

    @Test
    void signedPaymentFailureAndCancellationUseWebhookHistory() {
        var failed = createPayment();
        var failure = intentEvent(FAILED, reference(), failed.payment());
        failure.dataObject().putObject("last_payment_error").put("decline_code", "insufficient_funds");
        receiveOk(failure);
        assertHistory(failed, "FAILED", FAILED);
        assertThat(get(failed.paymentPath()).path("failureReason").asText()).isEqualTo("insufficient_funds");

        var voided = createPayment();
        receiveOk(intentEvent(VOIDED, reference(), voided.payment()));
        assertHistory(voided, "VOIDED", VOIDED);
        assertThat(state(voided.paymentPath())).isEqualTo("VOIDED");
        verifyNoInteractions(processor);
        assertReconciles();
    }

    @Test
    void missingInvalidAndBodyMismatchedSignaturesStoreNothing() {
        var flow = createPayment();
        var event = intentEvent(AUTHORIZED, reference(), flow.payment());
        String body = event.raw();
        assertError(send(body, null), HttpStatus.BAD_REQUEST, "validation_error");
        assertError(send(body, signature(body, UUID.randomUUID().toString())), HttpStatus.BAD_REQUEST, "validation_error");
        assertError(send(body + " ", signature(body, SIGNING_SECRET)), HttpStatus.BAD_REQUEST, "validation_error");

        assertThat(eventRows(event.id())).isZero();
        assertThat(state(flow.paymentPath())).isEqualTo("CREATED");
        verifyNoInteractions(processor);
    }

    @Test
    void duplicateEventIdDoesNotApplyTheTransitionAgain() {
        var flow = createPayment();
        var event = intentEvent(AUTHORIZED, reference(), flow.payment());
        receiveOk(event);
        var history = get(flow.paymentPath() + "/history");
        receiveOk(event);

        assertThat(eventRows(event.id())).isEqualTo(1);
        assertThat(get(flow.paymentPath() + "/history")).isEqualTo(history);
        assertThat(history).hasSize(2);
        verifyNoInteractions(processor);
    }

    @Test
    void unmatchedEventStaysUnprocessedAndCanBeRetriedAfterPaymentExists() {
        UUID invoice = openInvoice();
        UUID payment = UUID.randomUUID();
        String reference = reference();
        var event = intentEvent(AUTHORIZED, reference, payment);

        assertError(send(event), HttpStatus.NOT_FOUND, "not_found");
        assertThat(eventRows(event.id())).isEqualTo(1);
        assertThat(processedRows(event.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT payload ->> 'id' FROM webhook_events WHERE event_id = ?",
                String.class, event.id())).isEqualTo(event.id());

        jdbc.update("""
                INSERT INTO payments (id, invoice_id, amount, currency, status, processor, payment_method_token)
                VALUES (?, ?, 500, 'USD', 'CREATED', 'STRIPE', 'pm_card_visa')
                """, payment, invoice);
        receiveOk(event);

        var flow = new Flow(invoice, payment);
        assertThat(get(flow.paymentPath()).path("processorReference").asText()).isEqualTo(reference);
        assertHistory(flow, "AUTHORIZED", AUTHORIZED);
        verifyNoInteractions(processor);
    }

    @Test
    void alreadyAppliedInvalidAndUnhandledEventsAreMarkedProcessed() {
        var flow = createPayment();
        String reference = reference();
        receiveOk(intentEvent(AUTHORIZED, reference, flow.payment()));
        var history = get(flow.paymentPath() + "/history");

        receiveOk(intentEvent(AUTHORIZED, reference, flow.payment()));
        receiveOk(intentEvent(FAILED, reference, flow.payment()));
        var unrelated = event("customer.created", json.createObjectNode().put("id", "cus_fixture"));
        receiveOk(unrelated);

        assertThat(get(flow.paymentPath() + "/history")).isEqualTo(history);
        assertThat(state(flow.paymentPath())).isEqualTo("AUTHORIZED");
        verifyNoInteractions(processor);
    }

    @Test
    void concurrentDeliveriesOfTheSameEventBothSucceedWithOnePosting() throws Exception {
        var flow = createPayment();
        String reference = reference();
        receiveOk(intentEvent(AUTHORIZED, reference, flow.payment()));
        var event = intentEvent(CAPTURED, reference, flow.payment());
        String body = event.raw();
        String signature = signature(body, SIGNING_SECRET);
        var start = new CyclicBarrier(2);
        var threads = Executors.newFixedThreadPool(2);

        try {
            var first = threads.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return send(body, signature);
            });
            var second = threads.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return send(body, signature);
            });
            assertThat(first.get(20, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(second.get(20, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
        } finally {
            threads.shutdownNow();
        }

        assertThat(processedRows(event.id())).isEqualTo(1);
        assertThat(get(flow.paymentPath() + "/history")).hasSize(3);
        assertHistory(flow, "CAPTURED", CAPTURED);
        assertPostingCount(flow, "PAYMENT_CAPTURED", 1);
        assertThat(state(flow.invoicePath())).isEqualTo("PAID");
        verifyNoInteractions(processor);
        assertReconciles();
    }

    @Test
    void matchingWebhookAfterApiCaptureDoesNotDoubleApply() {
        var flow = createPayment();
        var authorized = ok(action(flow, "authorize"));
        ok(action(flow, "capture"));
        var history = get(flow.paymentPath() + "/history");
        clearInvocations(processor);

        receiveOk(intentEvent(CAPTURED, authorized.path("processorReference").asText(), flow.payment()));

        assertThat(get(flow.paymentPath() + "/history")).isEqualTo(history);
        assertPostingCount(flow, "PAYMENT_CAPTURED", 1);
        verifyNoInteractions(processor);
        assertReconciles();
    }

    @Test
    void existingCapturePostingMakesWebhookANoOp() {
        var flow = createPayment();
        String reference = reference();
        receiveOk(intentEvent(AUTHORIZED, reference, flow.payment()));
        var kind = LedgerKind.PAYMENT_CAPTURED;
        ledger.post(kind, flow.payment(), "USD", kind.entries(500));

        receiveOk(intentEvent(CAPTURED, reference, flow.payment()));

        assertThat(state(flow.paymentPath())).isEqualTo("AUTHORIZED");
        assertThat(state(flow.invoicePath())).isEqualTo("OPEN");
        assertThat(get(flow.paymentPath() + "/history")).hasSize(2);
        assertPostingCount(flow, "PAYMENT_CAPTURED", 1);
        verifyNoInteractions(processor);
    }

    @Test
    void databaseFailureReturns500AndDoesNotCommitAnyPartOfTheDelivery() {
        var flow = createPayment();
        String reference = reference();
        receiveOk(intentEvent(AUTHORIZED, reference, flow.payment()));
        var event = intentEvent(CAPTURED, reference, flow.payment());
        jdbc.update("UPDATE invoices SET status = 'VOID' WHERE id = ?", flow.invoice());

        assertError(send(event), HttpStatus.INTERNAL_SERVER_ERROR, "internal_error");
        assertThat(eventRows(event.id())).isZero();
        assertThat(state(flow.paymentPath())).isEqualTo("AUTHORIZED");
        assertThat(get(flow.paymentPath() + "/history")).hasSize(2);
        assertPostingCount(flow, "PAYMENT_CAPTURED", 0);

        jdbc.update("UPDATE invoices SET status = 'OPEN' WHERE id = ?", flow.invoice());
        receiveOk(event);
        assertThat(state(flow.paymentPath())).isEqualTo("CAPTURED");
        assertReconciles();
        verifyNoInteractions(processor);
    }

    private void receiveOk(Event event) {
        assertThat(send(event).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(processedRows(event.id())).isEqualTo(1);
    }

    private ResponseEntity<JsonNode> send(Event event) {
        String raw = event.raw();
        return send(raw, signature(raw, SIGNING_SECRET));
    }

    private ResponseEntity<JsonNode> send(String raw, String signature) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (signature != null) {
            headers.set("Stripe-Signature", signature);
        }
        return http.postForEntity("/webhooks/stripe", new HttpEntity<>(raw, headers), JsonNode.class);
    }

    private static String signature(String raw, String signingSecret) {
        try {
            long timestamp = Instant.now().getEpochSecond();
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(signingSecret.getBytes(UTF_8), "HmacSHA256"));
            String digest = HexFormat.of().formatHex(hmac.doFinal((timestamp + "." + raw).getBytes(UTF_8)));
            return "t=" + timestamp + ",v1=" + digest;
        } catch (GeneralSecurityException exception) {
            throw new AssertionError("Could not sign test webhook.", exception);
        }
    }

    private Event intentEvent(String type, String reference, UUID payment) {
        var object = json.createObjectNode().put("object", "payment_intent").put("id", reference);
        if (payment != null) {
            object.putObject("metadata").put("paymentId", payment.toString());
        }
        return event(type, object);
    }

    private Event chargeRefunded(String reference) {
        var object = json.createObjectNode().put("object", "charge")
                .put("id", "ch_fixture").put("payment_intent", reference);
        return event(REFUNDED, object);
    }

    private Event event(String type, ObjectNode object) {
        String id = "evt_" + UUID.randomUUID().toString().replace("-", "");
        var root = json.createObjectNode().put("id", id).put("object", "event")
                .put("livemode", false).put("type", type);
        root.putObject("data").set("object", object);
        return new Event(id, root, object);
    }

    private Flow createPayment() {
        UUID invoice = openInvoice();
        var response = http.postForEntity(API + "/invoices/" + invoice + "/payments",
                keyed(Map.of("paymentMethodToken", "tok_success")), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new Flow(invoice, UUID.fromString(response.getBody().path("id").asText()));
    }

    private UUID openInvoice() {
        var customer = http.postForEntity(API + "/customers",
                Map.of("name", "Webhook Test Customer", "email", UUID.randomUUID() + "@example.com"), JsonNode.class);
        assertThat(customer.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var invoice = http.postForEntity(API + "/invoices", Map.of(
                "customerId", customer.getBody().path("id").asText(),
                "currency", "USD", "lineItems", List.of(Map.of("description", "Item", "quantity", 1, "unitAmount", 500))),
                JsonNode.class);
        assertThat(invoice.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString(invoice.getBody().path("id").asText());
        ok(http.postForEntity(API + "/invoices/" + id + "/finalize", null, JsonNode.class));
        return id;
    }

    private ResponseEntity<JsonNode> action(Flow flow, String action) {
        return http.postForEntity(flow.paymentPath() + "/" + action, keyed(null), JsonNode.class);
    }

    private static HttpEntity<Object> keyed(Object body) {
        var headers = new HttpHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return new HttpEntity<>(body, headers);
    }

    private static JsonNode ok(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private JsonNode get(String path) {
        return ok(http.getForEntity(path, JsonNode.class));
    }

    private String state(String path) {
        return get(path).path("status").asText();
    }

    private static void assertError(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("error").path("code").asText()).isEqualTo(code);
    }

    private void assertHistory(Flow flow, String status, String type) {
        var history = get(flow.paymentPath() + "/history");
        JsonNode newest = history.get(history.size() - 1);
        assertThat(newest.path("toStatus").asText()).isEqualTo(status);
        assertThat(newest.path("actor").asText()).isEqualTo("WEBHOOK");
        assertThat(newest.path("reason").asText()).isEqualTo(type);
    }

    private void assertPostingCount(Flow flow, String kind, long count) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions WHERE reference_id = ? AND kind = ?",
                Long.class, flow.payment(), kind)).isEqualTo(count);
    }

    private long eventRows(String id) {
        return jdbc.queryForObject("SELECT count(*) FROM webhook_events WHERE event_id = ?", Long.class, id);
    }

    private long processedRows(String id) {
        return jdbc.queryForObject("SELECT count(*) FROM webhook_events WHERE event_id = ? AND processed_at IS NOT NULL",
                Long.class, id);
    }

    private void assertReconciles() {
        var report = ok(http.postForEntity(API + "/admin/reconciliation/run", null, JsonNode.class));
        assertThat(report.path("status").asText()).isEqualTo("OK");
        assertThat(report.path("details")).isEmpty();
    }

    private static String reference() {
        return "pi_" + UUID.randomUUID().toString().replace("-", "");
    }

    private record Flow(UUID invoice, UUID payment) {
        String invoicePath() {
            return API + "/invoices/" + invoice;
        }

        String paymentPath() {
            return API + "/payments/" + payment;
        }
    }

    private record Event(String id, ObjectNode payload, ObjectNode dataObject) {
        String raw() {
            return " " + payload + "\n";
        }
    }

    static class SigningSecretInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            var secrets = Map.<String, Object>of("STRIPE_WEBHOOK_SECRET", SIGNING_SECRET);
            String name = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
            context.getEnvironment().getPropertySources().replace(name, new SystemEnvironmentPropertySource(name, secrets));
        }
    }
}
