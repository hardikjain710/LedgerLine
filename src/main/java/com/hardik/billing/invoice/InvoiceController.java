package com.hardik.billing.invoice;

import com.hardik.billing.common.PageResponse;
import com.hardik.billing.invoice.dto.CreateInvoiceRequest;
import com.hardik.billing.invoice.dto.InvoiceResponse;
import com.hardik.billing.invoice.service.InvoiceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/invoices")
public class InvoiceController {

    private final InvoiceService invoices;

    public InvoiceController(InvoiceService invoices) {
        this.invoices = invoices;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceResponse create(@Valid @RequestBody CreateInvoiceRequest request) {
        return invoices.create(request);
    }

    @GetMapping("/{id}")
    public InvoiceResponse get(@PathVariable UUID id) {
        return invoices.get(id);
    }

    @GetMapping
    public PageResponse<InvoiceResponse> list(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) InvoiceStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return invoices.list(customerId, status, page, size);
    }

    @PostMapping("/{id}/finalize")
    public InvoiceResponse finalizeInvoice(@PathVariable UUID id) {
        return invoices.finalizeInvoice(id);
    }

    @PostMapping("/{id}/void")
    public InvoiceResponse voidInvoice(@PathVariable UUID id) {
        return invoices.voidInvoice(id);
    }
}
