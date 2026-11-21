package com.hardik.billing.reconciliation;

import com.hardik.billing.reconciliation.dto.ReconciliationReportResponse;
import com.hardik.billing.reconciliation.service.ReconciliationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/reconciliation")
public class ReconciliationController {

    private final ReconciliationService reconciliation;

    public ReconciliationController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @PostMapping("/run")
    public ReconciliationReportResponse run() {
        return reconciliation.run();
    }

    @GetMapping("/latest")
    public ReconciliationReportResponse latest() {
        return reconciliation.latest();
    }
}
