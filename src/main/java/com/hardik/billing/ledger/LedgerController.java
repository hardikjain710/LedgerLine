package com.hardik.billing.ledger;

import com.hardik.billing.ledger.dto.LedgerBalanceResponse;
import com.hardik.billing.ledger.dto.LedgerTransactionResponse;
import com.hardik.billing.ledger.service.LedgerService;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    private final LedgerService ledger;

    public LedgerController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/balances")
    public List<LedgerBalanceResponse> balances() {
        return ledger.balances();
    }

    @GetMapping("/transactions")
    public List<LedgerTransactionResponse> transactions(
            @RequestParam
            @Pattern(regexp = "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
            String referenceId) {
        return ledger.transactions(UUID.fromString(referenceId));
    }
}
