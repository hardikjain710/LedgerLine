package com.hardik.billing.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReconciliationReportRepository extends JpaRepository<ReconciliationReport, UUID> {

    Optional<ReconciliationReport> findFirstByOrderByRunAtDescIdDesc();
}
