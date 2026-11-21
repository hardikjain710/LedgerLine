package com.hardik.billing;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BillingApplicationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private final TestRestTemplate http;
    private final JdbcTemplate jdbc;

    @Autowired
    BillingApplicationTest(TestRestTemplate http, JdbcTemplate jdbc) {
        this.http = http;
        this.jdbc = jdbc;
    }

    @Test
    void reportsHealthyAfterMigratingAnEmptyDatabase() {
        var response = http.getForEntity("/actuator/health", HealthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new HealthResponse("UP"));
        assertThat(jdbc.queryForList(
                "SELECT code FROM ledger_accounts ORDER BY id", String.class))
                .containsExactly("ACCOUNTS_RECEIVABLE", "CASH", "REVENUE", "REFUNDS");
        assertThat(jdbc.queryForObject("SELECT count(line_number) FROM invoice_line_items", Long.class))
                .isZero();
    }

    record HealthResponse(String status) {
    }
}
