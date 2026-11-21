package com.hardik.billing.reconciliation;

import com.hardik.billing.reconciliation.dto.ReconciliationDetail;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_reports")
public class ReconciliationReport {

    public enum Status {
        OK,
        MISMATCH
    }

    @Id
    private UUID id;

    @Column(nullable = false)
    private Instant runAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<ReconciliationDetail> details;

    protected ReconciliationReport() {
    }

    public ReconciliationReport(Instant runAt, List<ReconciliationDetail> details) {
        this.id = UUID.randomUUID();
        this.runAt = runAt;
        this.status = details.isEmpty() ? Status.OK : Status.MISMATCH;
        this.details = List.copyOf(details);
    }

    public UUID getId() {
        return id;
    }

    public Instant getRunAt() {
        return runAt;
    }

    public Status getStatus() {
        return status;
    }

    public List<ReconciliationDetail> getDetails() {
        return List.copyOf(details);
    }
}
