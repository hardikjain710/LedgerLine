package com.hardik.billing.reconciliation.dto;

import com.hardik.billing.reconciliation.ReconciliationReport;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReconciliationReportResponse(
        UUID id, Instant runAt, ReconciliationReport.Status status, List<ReconciliationDetail> details
) {
    public static ReconciliationReportResponse from(ReconciliationReport report) {
        return new ReconciliationReportResponse(report.getId(), report.getRunAt(), report.getStatus(), report.getDetails());
    }
}
