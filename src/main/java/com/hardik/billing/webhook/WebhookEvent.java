package com.hardik.billing.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "webhook_events")
public class WebhookEvent {

    @Id
    @Column(length = 255)
    private String eventId;

    @Column(nullable = false, length = 100)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private JsonNode payload;

    @Column(nullable = false)
    private Instant receivedAt;

    private Instant processedAt;

    protected WebhookEvent() {
    }

    public boolean isProcessed() {
        return processedAt != null;
    }

    public void markProcessed(Instant at) {
        processedAt = at;
    }
}
