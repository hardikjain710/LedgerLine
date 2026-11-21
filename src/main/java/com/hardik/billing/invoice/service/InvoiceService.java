package com.hardik.billing.invoice.service;

import com.hardik.billing.common.BillingException;
import com.hardik.billing.common.ErrorCode;
import com.hardik.billing.common.PageResponse;
import com.hardik.billing.customer.CustomerRepository;
import com.hardik.billing.invoice.Invoice;
import com.hardik.billing.invoice.InvoiceLineItem;
import com.hardik.billing.invoice.InvoiceRepository;
import com.hardik.billing.invoice.InvoiceStatus;
import com.hardik.billing.invoice.dto.CreateInvoiceRequest;
import com.hardik.billing.invoice.dto.InvoiceResponse;
import com.hardik.billing.ledger.LedgerKind;
import com.hardik.billing.ledger.service.LedgerService;
import com.hardik.billing.payment.PaymentRepository;
import com.hardik.billing.payment.PaymentStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class InvoiceService {

    private final InvoiceRepository invoices;
    private final CustomerRepository customers;
    private final PaymentRepository payments;
    private final LedgerService ledger;

    public InvoiceService(InvoiceRepository invoices, CustomerRepository customers, PaymentRepository payments,
                          LedgerService ledger) {
        this.invoices = invoices;
        this.customers = customers;
        this.payments = payments;
        this.ledger = ledger;
    }

    @Transactional
    public InvoiceResponse create(CreateInvoiceRequest request) {
        if (!customers.existsById(request.customerId())) {
            throw new BillingException(ErrorCode.NOT_FOUND, "Customer was not found.");
        }

        long totalAmount = calculateTotal(request.lineItems());
        List<InvoiceLineItem> items = new ArrayList<>();
        for (var item : request.lineItems()) {
            items.add(new InvoiceLineItem(item.description(), item.quantity(), item.unitAmount(), items.size() + 1));
        }
        var invoice = new Invoice(request.customerId(), request.currency(), totalAmount,
                Instant.now().truncatedTo(ChronoUnit.MICROS), items);
        return toResponse(invoices.save(invoice));
    }

    static long calculateTotal(List<CreateInvoiceRequest.LineItem> items) {
        long total = 0;
        try {
            for (var item : items) {
                long lineAmount = Math.multiplyExact((long) item.quantity(), item.unitAmount());
                total = Math.addExact(total, lineAmount);
            }
            return total;
        } catch (ArithmeticException exception) {
            throw new BillingException(ErrorCode.VALIDATION_ERROR, "Invoice total exceeds the supported amount.");
        }
    }

    public InvoiceResponse get(UUID id) {
        return toResponse(findInvoice(id));
    }

    public PageResponse<InvoiceResponse> list(UUID customerId, InvoiceStatus status, int page, int size) {
        Specification<Invoice> filters = (root, query, criteria) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (customerId != null) {
                predicates.add(criteria.equal(root.get("customerId"), customerId));
            }
            if (status != null) {
                predicates.add(criteria.equal(root.get("status"), status));
            }
            return criteria.and(predicates.toArray(Predicate[]::new));
        };
        return PageResponse.from(invoices.findAll(filters, PageResponse.pageRequest(page, size))
                .map(InvoiceService::toResponse));
    }

    @Transactional
    public InvoiceResponse finalizeInvoice(UUID id) {
        Invoice invoice = findInvoice(id);
        invoice.transitionTo(InvoiceStatus.OPEN, Instant.now().truncatedTo(ChronoUnit.MICROS));
        if (invoice.getTotalAmount() == 0) {
            throw new BillingException(ErrorCode.VALIDATION_ERROR, "Invoice total must be greater than zero.");
        }
        postInvoice(LedgerKind.INVOICE_FINALIZED, invoice);
        return toResponse(invoice);
    }

    @Transactional
    public InvoiceResponse voidInvoice(UUID id) {
        Invoice invoice = invoices.findByIdForUpdate(id)
                .orElseThrow(() -> new BillingException(ErrorCode.NOT_FOUND, "Invoice was not found."));
        boolean wasOpen = invoice.getStatus() == InvoiceStatus.OPEN;
        if (wasOpen && payments.existsByInvoiceIdAndStatusIn(id, PaymentStatus.ACTIVE)) {
            throw new BillingException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot void an open invoice with an active payment.");
        }
        invoice.transitionTo(InvoiceStatus.VOID, Instant.now().truncatedTo(ChronoUnit.MICROS));
        if (wasOpen) {
            postInvoice(LedgerKind.INVOICE_VOIDED, invoice);
        }
        return toResponse(invoice);
    }

    private void postInvoice(LedgerKind kind, Invoice invoice) {
        ledger.post(kind, invoice.getId(), invoice.getCurrency(), kind.entries(invoice.getTotalAmount()));
    }

    private Invoice findInvoice(UUID id) {
        return invoices.findById(id)
                .orElseThrow(() -> new BillingException(ErrorCode.NOT_FOUND, "Invoice was not found."));
    }

    private static InvoiceResponse toResponse(Invoice invoice) {
        var items = invoice.getLineItems().stream()
                .map(item -> new InvoiceResponse.LineItem(item.getId(), item.getDescription(),
                        item.getQuantity(), item.getUnitAmount(), item.getLineNumber()))
                .toList();
        return new InvoiceResponse(invoice.getId(), invoice.getCustomerId(), invoice.getStatus(), invoice.getCurrency(),
                invoice.getTotalAmount(), items, invoice.getCreatedAt(), invoice.getFinalizedAt());
    }
}
