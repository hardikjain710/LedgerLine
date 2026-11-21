package com.hardik.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.hardik.billing.ledger.LedgerKind;
import com.hardik.billing.ledger.dto.LedgerBalanceResponse;
import com.hardik.billing.ledger.service.LedgerService;
import com.hardik.billing.reconciliation.dto.ReconciliationReportResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "billing.processor=simulated")
@Sql(statements = "TRUNCATE customers, ledger_transactions, reconciliation_reports, idempotency_keys CASCADE")
class LedgerReconciliationApiTest {

    private static final String API = "/api/v1";
    private static final String BALANCES = API + "/ledger/balances";
    private static final String TRANSACTIONS = API + "/ledger/transactions";
    private static final String RECONCILIATION = API + "/admin/reconciliation";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private final TestRestTemplate http;
    private final JdbcTemplate jdbc;
    private final LedgerService ledger;

    @Autowired
    LedgerReconciliationApiTest(TestRestTemplate http, JdbcTemplate jdbc, LedgerService ledger) {
        this.http = http;
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    @Test
    void postsAndReconcilesTheFullPaymentFlow() {
        UUID invoice = draftInvoice("USD", 250);
        assertBalances("USD", 0, 0, 0, 0);
        finalizeInvoice(invoice);
        assertBalances("USD", 250, 0, 250, 0);

        var finalization = onlyTransaction(invoice, "INVOICE_FINALIZED", "USD");
        assertEntry(finalization.path("entries").get(0), "ACCOUNTS_RECEIVABLE", "DEBIT", 250);
        assertEntry(finalization.path("entries").get(1), "REVENUE", "CREDIT", 250);

        UUID payment = createPayment(invoice);
        assertBalances("USD", 250, 0, 250, 0);
        assertThat(action(payment, "authorize").path("status").asText()).isEqualTo("AUTHORIZED");
        assertThat(transactions(payment)).isEmpty();
        assertBalances("USD", 250, 0, 250, 0);

        assertThat(action(payment, "capture").path("status").asText()).isEqualTo("CAPTURED");
        assertThat(get(invoicePath(invoice)).path("status").asText()).isEqualTo("PAID");
        assertBalances("USD", 0, 250, 250, 0);
        var capture = onlyTransaction(payment, "PAYMENT_CAPTURED", "USD");
        assertEntry(capture.path("entries").get(0), "CASH", "DEBIT", 250);
        assertEntry(capture.path("entries").get(1), "ACCOUNTS_RECEIVABLE", "CREDIT", 250);

        assertThat(action(payment, "refund").path("status").asText()).isEqualTo("REFUNDED");
        assertThat(get(invoicePath(invoice)).path("status").asText()).isEqualTo("REFUNDED");
        assertBalances("USD", 0, 0, 250, 250);
        assertBalances("INR", 0, 0, 0, 0);
        var paymentPostings = transactions(payment);
        assertThat(paymentPostings).hasSize(2);
        var refund = paymentPostings.get(1);
        assertThat(refund.path("kind").asText()).isEqualTo("PAYMENT_REFUNDED");
        assertEntry(refund.path("entries").get(0), "REFUNDS", "DEBIT", 250);
        assertEntry(refund.path("entries").get(1), "CASH", "CREDIT", 250);
        var report = run();
        assertThat(report.status().name()).isEqualTo("OK");
        assertThat(report.details()).isEmpty();
    }

    @Test
    void postsOnlyForOpenInvoiceVoidAndNeverForPaymentVoid() {
        UUID draft = draftInvoice("USD", 300);
        assertThat(body(post(invoicePath(draft) + "/void", null), HttpStatus.OK).path("status").asText()).isEqualTo("VOID");
        assertThat(transactions(draft)).isEmpty();

        UUID open = openInvoice("USD", 300);
        UUID payment = createPayment(open);
        action(payment, "authorize");
        action(payment, "void");
        assertThat(transactions(payment)).isEmpty();
        assertBalances("USD", 300, 0, 300, 0);

        assertThat(body(post(invoicePath(open) + "/void", null), HttpStatus.OK).path("status").asText()).isEqualTo("VOID");
        var postings = transactions(open);
        assertThat(postings).hasSize(2);
        assertThat(postings.get(0).path("kind").asText()).isEqualTo("INVOICE_FINALIZED");
        assertThat(postings.get(1).path("kind").asText()).isEqualTo("INVOICE_VOIDED");
        assertEntry(postings.get(1).path("entries").get(0), "REVENUE", "DEBIT", 300);
        assertEntry(postings.get(1).path("entries").get(1), "ACCOUNTS_RECEIVABLE", "CREDIT", 300);
        assertBalances("USD", 0, 0, 0, 0);
        assertThat(run().details()).isEmpty();
    }

    @Test
    void emitsAllEightBalancesInOrderAndSeparatesCurrencies() {
        assertThat(balances()).containsExactly(
                new LedgerBalanceResponse("ACCOUNTS_RECEIVABLE", "INR", 0),
                new LedgerBalanceResponse("ACCOUNTS_RECEIVABLE", "USD", 0),
                new LedgerBalanceResponse("CASH", "INR", 0),
                new LedgerBalanceResponse("CASH", "USD", 0),
                new LedgerBalanceResponse("REFUNDS", "INR", 0),
                new LedgerBalanceResponse("REFUNDS", "USD", 0),
                new LedgerBalanceResponse("REVENUE", "INR", 0),
                new LedgerBalanceResponse("REVENUE", "USD", 0));

        openInvoice("INR", 125);
        openInvoice("USD", 400);
        assertBalances("INR", 125, 0, 125, 0);
        assertBalances("USD", 400, 0, 400, 0);
        assertThat(run().details()).isEmpty();
    }

    @Test
    void validatesReferenceIdAndOrdersTransactionsByTimeThenId() {
        assertError(http.getForEntity(TRANSACTIONS, JsonNode.class), HttpStatus.BAD_REQUEST, "validation_error");
        assertError(http.getForEntity(TRANSACTIONS + "?referenceId=nope", JsonNode.class),
                HttpStatus.BAD_REQUEST, "validation_error");
        assertError(http.getForEntity(TRANSACTIONS + "?referenceId=1-1-1-1-1", JsonNode.class),
                HttpStatus.BAD_REQUEST, "validation_error");
        assertThat(transactions(UUID.randomUUID())).isEmpty();

        UUID invoice = openInvoice("USD", 70);
        body(post(invoicePath(invoice) + "/void", null), HttpStatus.OK);
        assertThat(transactions(invoice)).extracting(row -> row.path("kind").asText())
                .containsExactly("INVOICE_FINALIZED", "INVOICE_VOIDED");

        jdbc.update("UPDATE ledger_transactions SET created_at = now() WHERE reference_id = ?", invoice);
        var tied = transactions(invoice);
        assertThat(tied).hasSize(2);
        assertThat(tied).extracting(row -> row.path("id").asText()).isSorted();
        assertThat(tied.get(0).path("createdAt")).isEqualTo(tied.get(1).path("createdAt"));
        assertThat(tied).allSatisfy(row -> assertThat(row.path("entries")).hasSize(2));
    }

    @Test
    void captureRollsBackPaymentInvoiceAndHistoryWhenPostingFails() {
        UUID invoice = openInvoice("USD", 250);
        UUID payment = createPayment(invoice);
        action(payment, "authorize");
        var kind = LedgerKind.PAYMENT_CAPTURED;

        // Force the real posting to hit the database unique constraint.
        ledger.post(kind, payment, "USD", kind.entries(250));
        assertError(paymentAction(payment, "capture"), HttpStatus.INTERNAL_SERVER_ERROR, "internal_error");

        assertThat(get(paymentPath(payment)).path("status").asText()).isEqualTo("AUTHORIZED");
        assertThat(get(invoicePath(invoice)).path("status").asText()).isEqualTo("OPEN");
        var history = get(paymentPath(payment) + "/history");
        assertThat(history).extracting(row -> row.path("toStatus").asText()).containsExactly("CREATED", "AUTHORIZED");
        var existing = onlyTransaction(payment, "PAYMENT_CAPTURED", "USD");
        assertThat(existing.path("entries")).hasSize(2);
    }

    @Test
    void directlyRejectsADuplicatePostingAndKeepsTheOriginalEntries() {
        UUID invoice = openInvoice("INR", 400);
        var kind = LedgerKind.INVOICE_FINALIZED;

        assertThatThrownBy(() -> ledger.post(kind, invoice, "INR", kind.entries(400)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(onlyTransaction(invoice, "INVOICE_FINALIZED", "INR").path("entries")).hasSize(2);
    }

    @Test
    void overflowingCurrencySumsReturnInternalError() {
        openInvoice("USD", Long.MAX_VALUE);
        openInvoice("USD", 1);

        assertError(http.getForEntity(BALANCES, JsonNode.class), HttpStatus.INTERNAL_SERVER_ERROR, "internal_error");
        assertError(post(RECONCILIATION + "/run", null), HttpStatus.INTERNAL_SERVER_ERROR, "internal_error");
        assertError(http.getForEntity(RECONCILIATION + "/latest", JsonNode.class), HttpStatus.NOT_FOUND, "not_found");
    }

    @Test
    void reconciliationReportsChangedEntryAmount() {
        UUID invoice = openInvoice("USD", 100);
        UUID transaction = transactionId(invoice, LedgerKind.INVOICE_FINALIZED);
        jdbc.update("UPDATE ledger_entries SET amount = 101 WHERE transaction_id = ? AND direction = 'DEBIT'", transaction);

        var report = run();
        assertMismatch(report, "TRANSACTION_BALANCED", transaction, "USD debits 101 do not equal credits 100");
        assertMismatch(report, "INVOICE_FINALIZED", invoice, "Expected INVOICE_FINALIZED entries");
        assertMismatch(report, "ACCOUNTS_RECEIVABLE", null, "open invoices total 100");
        var latest = http.getForEntity(RECONCILIATION + "/latest", ReconciliationReportResponse.class);
        assertThat(latest.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(latest.getBody()).isEqualTo(report);
    }

    @Test
    void reconciliationReportsMissingCapturedPaymentPosting() {
        UUID payment = createPayment(openInvoice("USD", 250));
        action(payment, "authorize");
        action(payment, "capture");
        UUID transaction = transactionId(payment, LedgerKind.PAYMENT_CAPTURED);
        jdbc.update("DELETE FROM ledger_entries WHERE transaction_id = ?", transaction);
        jdbc.update("DELETE FROM ledger_transactions WHERE id = ?", transaction);

        assertMismatch(run(), "PAYMENT_CAPTURED", payment, "found 0");
    }

    @Test
    void reconciliationReportsUnbalancedTransactionsEvenWhenTheyOffsetGlobally() {
        UUID inr = insertUnbalancedTransaction("INR", 100, 99);
        UUID usd = insertUnbalancedTransaction("USD", 99, 100);

        var report = run();
        assertMismatch(report, "TRANSACTION_BALANCED", inr, "INR debits 100 do not equal credits 99");
        assertMismatch(report, "TRANSACTION_BALANCED", usd, "USD debits 99 do not equal credits 100");
        assertMismatch(report, "CURRENCY_BALANCED", null, "INR debits 100 do not equal credits 99");
        assertMismatch(report, "CURRENCY_BALANCED", null, "USD debits 99 do not equal credits 100");
    }

    @Test
    void reconciliationReportsWrongAccountEvenWhenTransactionBalances() {
        UUID invoice = openInvoice("USD", 100);
        UUID transaction = transactionId(invoice, LedgerKind.INVOICE_FINALIZED);
        jdbc.update("""
                UPDATE ledger_entries SET account_id = (SELECT id FROM ledger_accounts WHERE code = 'REFUNDS')
                WHERE transaction_id = ? AND direction = 'CREDIT'
                """, transaction);

        var report = run();
        assertMismatch(report, "INVOICE_FINALIZED", invoice, "REFUNDS");
        assertThat(report.details()).hasSize(1);
    }

    @Test
    void reconciliationReportsPostingInTheWrongCurrencyEvenWhenItBalances() {
        UUID invoice = openInvoice("USD", 100);
        UUID transaction = transactionId(invoice, LedgerKind.INVOICE_FINALIZED);
        jdbc.update("UPDATE ledger_transactions SET currency = 'INR' WHERE id = ?", transaction);

        var report = run();
        assertMismatch(report, "INVOICE_FINALIZED", invoice, "posting currency USD, found INR");
        assertMismatch(report, "ACCOUNTS_RECEIVABLE", null, "in INR is 100 but open invoices total 0");
        assertMismatch(report, "ACCOUNTS_RECEIVABLE", null, "in USD is 0 but open invoices total 100");
        assertThat(report.details()).hasSize(3);
    }

    @Test
    void reconciliationReportsMissingRefundPostingWithCaptureStillPresent() {
        UUID payment = createPayment(openInvoice("USD", 250));
        action(payment, "authorize");
        action(payment, "capture");
        action(payment, "refund");
        UUID refund = transactionId(payment, LedgerKind.PAYMENT_REFUNDED);
        jdbc.update("DELETE FROM ledger_entries WHERE transaction_id = ?", refund);
        jdbc.update("DELETE FROM ledger_transactions WHERE id = ?", refund);

        var report = run();
        assertMismatch(report, "PAYMENT_REFUNDED", payment, "found 0");
        assertThat(report.details()).hasSize(1);
    }

    @Test
    void reconciliationReportsVoidPostingOnInvoiceThatWasNeverFinalized() {
        UUID invoice = draftInvoice("USD", 100);
        body(post(invoicePath(invoice) + "/void", null), HttpStatus.OK);
        var kind = LedgerKind.INVOICE_VOIDED;
        ledger.post(kind, invoice, "USD", kind.entries(100));

        assertMismatch(run(), "INVOICE_VOIDED", invoice, "never finalized");
    }

    @Test
    void latestReturnsNotFoundThenReturnsTheMostRecentReport() {
        assertError(http.getForEntity(RECONCILIATION + "/latest", JsonNode.class), HttpStatus.NOT_FOUND, "not_found");
        var first = run();
        var second = run();

        assertThat(first.id()).isNotEqualTo(second.id());
        var latest = http.getForEntity(RECONCILIATION + "/latest", ReconciliationReportResponse.class);
        assertThat(latest.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(latest.getBody()).isEqualTo(second);
    }

    private UUID insertUnbalancedTransaction(String currency, long debit, long credit) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO ledger_transactions (id, kind, reference_id, currency) VALUES (?, 'PAYMENT_CAPTURED', ?, ?)",
                id, UUID.randomUUID(), currency);
        jdbc.update("INSERT INTO ledger_entries (transaction_id, account_id, direction, amount) VALUES (?, 2, 'DEBIT', ?)",
                id, debit);
        jdbc.update("INSERT INTO ledger_entries (transaction_id, account_id, direction, amount) VALUES (?, 3, 'CREDIT', ?)",
                id, credit);
        return id;
    }

    private UUID transactionId(UUID reference, LedgerKind kind) {
        return jdbc.queryForObject("SELECT id FROM ledger_transactions WHERE reference_id = ? AND kind = ?",
                UUID.class, reference, kind.name());
    }

    private ReconciliationReportResponse run() {
        var response = http.postForEntity(RECONCILIATION + "/run", null, ReconciliationReportResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private static void assertMismatch(ReconciliationReportResponse report, String check, UUID entityId, String text) {
        assertThat(report.status().name()).isEqualTo("MISMATCH");
        assertThat(report.details()).anySatisfy(detail -> {
            assertThat(detail.check()).isEqualTo(check);
            assertThat(detail.entityId()).isEqualTo(entityId);
            assertThat(detail.message()).contains(text);
        });
    }

    private void assertBalances(String currency, long receivable, long cash, long revenue, long refunds) {
        assertThat(balances()).filteredOn(row -> row.currency().equals(currency)).containsExactly(
                new LedgerBalanceResponse("ACCOUNTS_RECEIVABLE", currency, receivable),
                new LedgerBalanceResponse("CASH", currency, cash),
                new LedgerBalanceResponse("REFUNDS", currency, refunds),
                new LedgerBalanceResponse("REVENUE", currency, revenue));
    }

    private List<LedgerBalanceResponse> balances() {
        var response = http.getForEntity(BALANCES, LedgerBalanceResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return Arrays.asList(response.getBody());
    }

    private JsonNode onlyTransaction(UUID reference, String kind, String currency) {
        var transactions = transactions(reference);
        assertThat(transactions).hasSize(1);
        var transaction = transactions.get(0);
        assertThat(transaction.path("kind").asText()).isEqualTo(kind);
        assertThat(transaction.path("referenceId").asText()).isEqualTo(reference.toString());
        assertThat(transaction.path("currency").asText()).isEqualTo(currency);
        assertThat(transaction.path("entries")).hasSize(2);
        return transaction;
    }

    private JsonNode transactions(UUID reference) {
        return get(TRANSACTIONS + "?referenceId=" + reference);
    }

    private static void assertEntry(JsonNode entry, String account, String direction, long amount) {
        assertThat(entry.path("accountCode").asText()).isEqualTo(account);
        assertThat(entry.path("direction").asText()).isEqualTo(direction);
        assertThat(entry.path("amount").asLong()).isEqualTo(amount);
    }

    private UUID openInvoice(String currency, long amount) {
        UUID invoice = draftInvoice(currency, amount);
        finalizeInvoice(invoice);
        return invoice;
    }

    private UUID draftInvoice(String currency, long amount) {
        var customer = body(post(API + "/customers",
                Map.of("name", "Ledger Test Customer", "email", UUID.randomUUID() + "@example.com")), HttpStatus.CREATED);
        var invoice = body(post(API + "/invoices",
                Map.of("customerId", customer.path("id").asText(), "currency", currency,
                        "lineItems", List.of(Map.of("description", "Item", "quantity", 1, "unitAmount", amount)))),
                HttpStatus.CREATED);
        return UUID.fromString(invoice.path("id").asText());
    }

    private void finalizeInvoice(UUID invoice) {
        assertThat(body(post(invoicePath(invoice) + "/finalize", null), HttpStatus.OK).path("status").asText())
                .isEqualTo("OPEN");
    }

    private UUID createPayment(UUID invoice) {
        var created = body(paymentPost(invoicePath(invoice) + "/payments", Map.of("paymentMethodToken", "tok_success")),
                HttpStatus.CREATED);
        return UUID.fromString(created.path("id").asText());
    }

    private JsonNode action(UUID payment, String action) {
        return body(paymentAction(payment, action), HttpStatus.OK);
    }

    private ResponseEntity<JsonNode> paymentAction(UUID payment, String action) {
        return paymentPost(paymentPath(payment) + "/" + action, null);
    }

    private ResponseEntity<JsonNode> paymentPost(String path, Object request) {
        var headers = new HttpHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return post(path, new HttpEntity<>(request, headers));
    }

    private ResponseEntity<JsonNode> post(String path, Object request) {
        return http.postForEntity(path, request, JsonNode.class);
    }

    private JsonNode get(String path) {
        return body(http.getForEntity(path, JsonNode.class), HttpStatus.OK);
    }

    private static String invoicePath(UUID id) {
        return API + "/invoices/" + id;
    }

    private static String paymentPath(UUID id) {
        return API + "/payments/" + id;
    }

    private static void assertError(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(body(response, status).path("error").path("code").asText()).isEqualTo(code);
    }

    private static JsonNode body(ResponseEntity<JsonNode> response, HttpStatus status) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }
}
