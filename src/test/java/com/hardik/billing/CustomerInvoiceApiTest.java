package com.hardik.billing;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Sql(statements = "TRUNCATE customers, ledger_transactions CASCADE")
class CustomerInvoiceApiTest {

    private static final String CUSTOMERS = "/api/v1/customers";
    private static final String INVOICES = "/api/v1/invoices";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private final TestRestTemplate http;

    @Autowired
    CustomerInvoiceApiTest(TestRestTemplate http) {
        this.http = http;
    }

    @Test
    void createsFetchesAndPaginatesCustomersAndRejectsNormalizedDuplicates() {
        var first = createCustomer("  FIRST@Example.com  ");
        var latest = createCustomer("latest@example.com");

        assertThat(first.path("name").asText()).isEqualTo("Example Customer");
        assertThat(first.path("email").asText()).isEqualTo("first@example.com");
        assertThat(Instant.parse(first.path("createdAt").asText())).isNotNull();
        assertThat(get(CUSTOMERS + "/" + id(first))).isEqualTo(first);

        var firstPage = get(CUSTOMERS + "?page=0&size=1");
        assertThat(firstPage.path("page").asInt()).isZero();
        assertThat(firstPage.path("size").asInt()).isEqualTo(1);
        assertThat(firstPage.path("totalElements").asInt()).isEqualTo(2);
        assertThat(firstPage.path("content")).hasSize(1);
        assertThat(firstPage.path("content").get(0)).isEqualTo(latest);
        assertThat(get(CUSTOMERS + "?page=1&size=1").path("content").get(0)).isEqualTo(first);

        assertError(post(CUSTOMERS, Map.of("name", "Duplicate", "email", "First@example.COM")),
                HttpStatus.CONFLICT, "duplicate_email");
    }

    @Test
    void createsFetchesFiltersFinalizesAndVoidsInvoices() {
        String customerId = id(createCustomer("invoice@example.com"));
        String otherCustomerId = id(createCustomer("other@example.com"));
        var draft = createInvoice(customerId, List.of(item("First", 2, 100L), item("Second", 1, 50L)));
        String url = INVOICES + "/" + id(draft);

        assertThat(draft.path("customerId").asText()).isEqualTo(customerId);
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.path("currency").asText()).isEqualTo("USD");
        assertThat(draft.path("totalAmount").asLong()).isEqualTo(250);
        assertThat(draft.path("finalizedAt").isNull()).isTrue();
        var items = draft.path("lineItems");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).path("description").asText()).isEqualTo("First");
        assertThat(items.get(0).path("quantity").asInt()).isEqualTo(2);
        assertThat(items.get(0).path("unitAmount").asLong()).isEqualTo(100);
        assertThat(items.get(0).path("lineNumber").asInt()).isEqualTo(1);
        assertThat(items.get(1).path("description").asText()).isEqualTo("Second");
        assertThat(items.get(1).path("lineNumber").asInt()).isEqualTo(2);
        assertThat(get(url)).isEqualTo(draft);

        var newerDraft = createInvoice(customerId, List.of(item("Newer", 1, 25L)));
        createInvoice(otherCustomerId, List.of(item("Other customer", 1, 50L)));
        var drafts = get(INVOICES + "?customerId=" + customerId + "&status=DRAFT&size=1");
        assertThat(drafts.path("totalElements").asInt()).isEqualTo(2);
        assertThat(drafts.path("content").get(0)).isEqualTo(newerDraft);
        assertThat(get(INVOICES + "?status=DRAFT").path("totalElements").asInt()).isEqualTo(3);

        var open = body(post(url + "/finalize", null), HttpStatus.OK);
        assertThat(open.path("status").asText()).isEqualTo("OPEN");
        assertThat(Instant.parse(open.path("finalizedAt").asText())).isNotNull();
        assertThat(get(INVOICES + "?customerId=" + customerId + "&status=OPEN").path("content").get(0))
                .isEqualTo(open);
        assertError(post(url + "/finalize", null), HttpStatus.CONFLICT, "invalid_state_transition");

        var voided = body(post(url + "/void", null), HttpStatus.OK);
        assertThat(voided.path("status").asText()).isEqualTo("VOID");
        assertThat(voided.path("finalizedAt")).isEqualTo(open.path("finalizedAt"));
        assertThat(get(url)).isEqualTo(voided);
        var voidedDraft = body(post(INVOICES + "/" + id(newerDraft) + "/void", null), HttpStatus.OK);
        assertThat(voidedDraft.path("status").asText()).isEqualTo("VOID");
        assertThat(voidedDraft.path("finalizedAt").isNull()).isTrue();
    }

    @Test
    void returnsValidationAndNotFoundErrors() {
        String unknownId = UUID.randomUUID().toString();
        assertError(http.getForEntity(CUSTOMERS + "/" + unknownId, JsonNode.class),
                HttpStatus.NOT_FOUND, "not_found");
        assertError(http.getForEntity(INVOICES + "/" + unknownId, JsonNode.class),
                HttpStatus.NOT_FOUND, "not_found");
        assertError(post(INVOICES, invoice(unknownId, List.of(item("Item", 1, 10L)))),
                HttpStatus.NOT_FOUND, "not_found");
        assertError(post(CUSTOMERS, Map.of("name", " ", "email", "bad-email")),
                HttpStatus.BAD_REQUEST, "validation_error");
        assertError(http.getForEntity(CUSTOMERS + "?size=101", JsonNode.class),
                HttpStatus.BAD_REQUEST, "validation_error");
        assertError(http.getForEntity(INVOICES + "?status=unknown", JsonNode.class),
                HttpStatus.BAD_REQUEST, "validation_error");

        String customerId = id(createCustomer("validation@example.com"));
        assertError(post(INVOICES, invoice(customerId, List.of(item("Invalid quantity", 0, 10L)))),
                HttpStatus.BAD_REQUEST, "validation_error");
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var fractionalAmount = new HttpEntity<>("""
                {"customerId":"%s","currency":"USD",
                 "lineItems":[{"description":"Fraction","quantity":1,"unitAmount":0.5}]}
                """.formatted(customerId), headers);
        assertError(post(INVOICES, fractionalAmount),
                HttpStatus.BAD_REQUEST, "validation_error");
        assertError(post(INVOICES, invoice(customerId, List.of(item("Overflow", 2, Long.MAX_VALUE)))),
                HttpStatus.BAD_REQUEST, "validation_error");
        var zero = createInvoice(customerId, List.of(item("Free", 1, 0L)));
        String url = INVOICES + "/" + id(zero);
        assertError(post(url + "/finalize", null), HttpStatus.BAD_REQUEST, "validation_error");
        assertThat(get(url)).isEqualTo(zero);
    }

    private JsonNode createCustomer(String email) {
        return body(post(CUSTOMERS, Map.of("name", "Example Customer", "email", email)), HttpStatus.CREATED);
    }

    private JsonNode createInvoice(String customerId, List<Map<String, Object>> items) {
        return body(post(INVOICES, invoice(customerId, items)), HttpStatus.CREATED);
    }

    private static Map<String, Object> invoice(String customerId, List<Map<String, Object>> items) {
        return Map.of("customerId", customerId, "currency", "USD", "lineItems", items);
    }

    private static Map<String, Object> item(String description, int quantity, long unitAmount) {
        return Map.of("description", description, "quantity", quantity, "unitAmount", unitAmount);
    }

    private ResponseEntity<JsonNode> post(String url, Object request) {
        return http.postForEntity(url, request, JsonNode.class);
    }

    private JsonNode get(String url) {
        return body(http.getForEntity(url, JsonNode.class), HttpStatus.OK);
    }

    private static String id(JsonNode node) {
        return node.path("id").asText();
    }

    private static JsonNode body(ResponseEntity<JsonNode> response, HttpStatus status) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private static void assertError(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        var error = body(response, status).path("error");
        assertThat(error.path("code").asText()).isEqualTo(code);
        assertThat(error.path("message").asText()).isNotBlank();
    }
}
