package com.hardik.billing.reconciliation.service;

import com.hardik.billing.common.BillingException;
import com.hardik.billing.common.ErrorCode;
import com.hardik.billing.invoice.InvoiceRepository;
import com.hardik.billing.invoice.InvoiceStatus;
import com.hardik.billing.ledger.LedgerAccount;
import com.hardik.billing.ledger.LedgerKind;
import com.hardik.billing.ledger.LedgerTotals;
import com.hardik.billing.ledger.dto.LedgerTransactionResponse;
import com.hardik.billing.ledger.service.LedgerService;
import com.hardik.billing.payment.PaymentRepository;
import com.hardik.billing.payment.PaymentStatus;
import com.hardik.billing.reconciliation.ReconciliationReport;
import com.hardik.billing.reconciliation.ReconciliationReportRepository;
import com.hardik.billing.reconciliation.dto.ReconciliationDetail;
import com.hardik.billing.reconciliation.dto.ReconciliationReportResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class ReconciliationService {

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final LedgerService ledger;
    private final ReconciliationReportRepository reports;

    public ReconciliationService(InvoiceRepository invoices, PaymentRepository payments,
                                 LedgerService ledger, ReconciliationReportRepository reports) {
        this.invoices = invoices;
        this.payments = payments;
        this.ledger = ledger;
        this.reports = reports;
    }

    // A concurrent posting must be visible to all checks or to none of them.
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReportResponse run() {
        Instant runAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var details = new ArrayList<ReconciliationDetail>();
        var postings = checkLedger(details);
        checkPayments(postings, details);
        checkInvoicesAndReceivables(postings, details);
        return ReconciliationReportResponse.from(reports.save(new ReconciliationReport(runAt, details)));
    }

    @Transactional(readOnly = true)
    public ReconciliationReportResponse latest() {
        return reports.findFirstByOrderByRunAtDescIdDesc().map(ReconciliationReportResponse::from)
                .orElseThrow(() -> new BillingException(ErrorCode.NOT_FOUND, "No reconciliation report was found."));
    }

    private Map<PostingKey, List<LedgerTransactionResponse>> checkLedger(List<ReconciliationDetail> details) {
        Map<PostingKey, List<LedgerTransactionResponse>> postings = new HashMap<>();
        Map<String, LedgerTotals> byCurrency = new TreeMap<>();

        for (var transaction : ledger.allTransactions()) {
            var key = new PostingKey(transaction.kind(), transaction.referenceId());
            postings.computeIfAbsent(key, ignored -> new ArrayList<>()).add(transaction);
            var totals = new LedgerTotals();
            var currencyTotals = byCurrency.computeIfAbsent(transaction.currency(), ignored -> new LedgerTotals());
            for (var entry : transaction.entries()) {
                totals.add(entry.direction(), entry.amount());
                currencyTotals.add(entry.direction(), entry.amount());
            }
            if (transaction.entries().size() < 2) {
                details.add(new ReconciliationDetail("TRANSACTION_ENTRY_COUNT", transaction.id(),
                        "Ledger transaction has fewer than two entries."));
            }
            checkBalanced("TRANSACTION_BALANCED", transaction.id(), transaction.currency(), totals, details);
        }

        byCurrency.forEach((currency, totals) -> checkBalanced("CURRENCY_BALANCED", null, currency, totals, details));
        return postings;
    }

    private void checkPayments(Map<PostingKey, List<LedgerTransactionResponse>> postings,
                               List<ReconciliationDetail> details) {
        for (var payment : payments.findAllByStatusInOrderByIdAsc(EnumSet.of(PaymentStatus.CAPTURED, PaymentStatus.REFUNDED))) {
            checkPosting(postings, LedgerKind.PAYMENT_CAPTURED, payment.getId(),
                    payment.getCurrency(), payment.getAmount(), details);
            if (payment.getStatus() == PaymentStatus.REFUNDED) {
                checkPosting(postings, LedgerKind.PAYMENT_REFUNDED, payment.getId(),
                        payment.getCurrency(), payment.getAmount(), details);
            }
        }
    }

    private void checkInvoicesAndReceivables(Map<PostingKey, List<LedgerTransactionResponse>> postings,
                                             List<ReconciliationDetail> details) {
        Map<String, Long> openTotals = new HashMap<>();
        var statuses = EnumSet.of(InvoiceStatus.OPEN, InvoiceStatus.PAID, InvoiceStatus.REFUNDED, InvoiceStatus.VOID);
        for (var invoice : invoices.findAllByStatusInOrderByIdAsc(statuses)) {
            if (invoice.getStatus() != InvoiceStatus.VOID) {
                checkPosting(postings, LedgerKind.INVOICE_FINALIZED, invoice.getId(),
                        invoice.getCurrency(), invoice.getTotalAmount(), details);
            } else if (invoice.getFinalizedAt() != null) {
                checkPosting(postings, LedgerKind.INVOICE_VOIDED, invoice.getId(),
                        invoice.getCurrency(), invoice.getTotalAmount(), details);
            } else if (postings.containsKey(new PostingKey(LedgerKind.INVOICE_VOIDED, invoice.getId()))) {
                details.add(new ReconciliationDetail(LedgerKind.INVOICE_VOIDED.name(), invoice.getId(),
                        "Invoice was never finalized and must have no void posting."));
            }
            if (invoice.getStatus() == InvoiceStatus.OPEN) {
                openTotals.merge(invoice.getCurrency(), invoice.getTotalAmount(), Math::addExact);
            }
        }

        for (var balance : ledger.balances()) {
            if (LedgerAccount.ACCOUNTS_RECEIVABLE.equals(balance.accountCode())) {
                long expected = openTotals.getOrDefault(balance.currency(), 0L);
                if (balance.balance() != expected) {
                    details.add(new ReconciliationDetail(LedgerAccount.ACCOUNTS_RECEIVABLE, null,
                            "Accounts receivable in " + balance.currency() + " is " + balance.balance()
                                    + " but open invoices total " + expected + "."));
                }
            }
        }
    }

    private static void checkPosting(Map<PostingKey, List<LedgerTransactionResponse>> postings, LedgerKind kind,
                                     UUID referenceId, String currency, long amount, List<ReconciliationDetail> details) {
        var matches = postings.getOrDefault(new PostingKey(kind, referenceId), List.of());
        if (matches.size() != 1) {
            details.add(new ReconciliationDetail(kind.name(), referenceId,
                    "Expected exactly one " + kind + " posting, found " + matches.size() + "."));
            return;
        }

        var transaction = matches.getFirst();
        if (!transaction.currency().equals(currency)) {
            details.add(new ReconciliationDetail(kind.name(), referenceId,
                    "Expected " + kind + " posting currency " + currency + ", found " + transaction.currency() + "."));
        }
        var expectedEntries = kind.entries(amount);
        if (transaction.entries().size() != expectedEntries.size() || !transaction.entries().containsAll(expectedEntries)) {
            details.add(new ReconciliationDetail(kind.name(), referenceId,
                    "Expected " + kind + " entries " + expectedEntries + ", found " + transaction.entries() + "."));
        }
    }

    private static void checkBalanced(String check, UUID transactionId, String currency, LedgerTotals totals,
                                      List<ReconciliationDetail> details) {
        if (!totals.isBalanced()) {
            details.add(new ReconciliationDetail(check, transactionId, currency + " debits " + totals.debits()
                    + " do not equal credits " + totals.credits() + "."));
        }
    }

    private record PostingKey(LedgerKind kind, UUID referenceId) {
    }
}
