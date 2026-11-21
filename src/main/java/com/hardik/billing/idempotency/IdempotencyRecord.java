package com.hardik.billing.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    enum Status {
        IN_PROGRESS,
        COMPLETED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String idempotencyKey;

    @Column(nullable = false, length = 255)
    private String scope;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    private Integer responseStatus;

    @Column(columnDefinition = "text")
    private String responseBody;

    @Column(nullable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
    }

    IdempotencyRecord(String key, String scope, String requestHash) {
        this.idempotencyKey = key;
        this.scope = scope;
        this.requestHash = requestHash;
        this.status = Status.IN_PROGRESS;
        this.createdAt = Instant.now();
    }

    void complete(int responseStatus, String responseBody) {
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.status = Status.COMPLETED;
    }

    Long getId() {
        return id;
    }

    String getRequestHash() {
        return requestHash;
    }

    Status getStatus() {
        return status;
    }

    Integer getResponseStatus() {
        return responseStatus;
    }

    String getResponseBody() {
        return responseBody;
    }
}
