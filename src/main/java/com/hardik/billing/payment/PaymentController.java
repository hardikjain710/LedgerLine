package com.hardik.billing.payment;

import com.hardik.billing.idempotency.IdempotencyService;
import com.hardik.billing.idempotency.RawPaymentRequestBody;
import com.hardik.billing.payment.dto.CreatePaymentRequest;
import com.hardik.billing.payment.dto.PaymentHistoryResponse;
import com.hardik.billing.payment.dto.PaymentResponse;
import com.hardik.billing.payment.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {

    private static final byte[] EMPTY_BODY = new byte[0];
    private final PaymentService payments;
    private final IdempotencyService idempotency;

    public PaymentController(PaymentService payments, IdempotencyService idempotency) {
        this.payments = payments;
        this.idempotency = idempotency;
    }

    @PostMapping("/invoices/{id}/payments")
    public ResponseEntity<byte[]> create(@PathVariable UUID id, @Valid @RequestBody CreatePaymentRequest request,
                                         @RequestAttribute(RawPaymentRequestBody.ATTRIBUTE) byte[] rawBody,
                                         HttpServletRequest http) {
        return execute(http, rawBody, HttpStatus.CREATED, () -> payments.create(id, request));
    }

    @GetMapping("/payments/{id}")
    public PaymentResponse get(@PathVariable UUID id) {
        return payments.get(id);
    }

    @GetMapping("/payments/{id}/history")
    public List<PaymentHistoryResponse> history(@PathVariable UUID id) {
        return payments.history(id);
    }

    @PostMapping("/payments/{id}/authorize")
    public ResponseEntity<byte[]> authorize(@PathVariable UUID id, HttpServletRequest http) {
        return execute(http, EMPTY_BODY, HttpStatus.OK, () -> payments.authorize(id));
    }

    @PostMapping("/payments/{id}/capture")
    public ResponseEntity<byte[]> capture(@PathVariable UUID id, HttpServletRequest http) {
        return execute(http, EMPTY_BODY, HttpStatus.OK, () -> payments.capture(id));
    }

    @PostMapping("/payments/{id}/void")
    public ResponseEntity<byte[]> voidPayment(@PathVariable UUID id, HttpServletRequest http) {
        return execute(http, EMPTY_BODY, HttpStatus.OK, () -> payments.voidPayment(id));
    }

    @PostMapping("/payments/{id}/refund")
    public ResponseEntity<byte[]> refund(@PathVariable UUID id, HttpServletRequest http) {
        return execute(http, EMPTY_BODY, HttpStatus.OK, () -> payments.refund(id));
    }

    private ResponseEntity<byte[]> execute(HttpServletRequest request, byte[] rawBody, HttpStatus status,
                                           Supplier<PaymentResponse> operation) {
        String scope = request.getMethod() + " " + request.getRequestURI();
        return idempotency.execute(request.getHeader("Idempotency-Key"), scope, rawBody, status, operation);
    }
}
