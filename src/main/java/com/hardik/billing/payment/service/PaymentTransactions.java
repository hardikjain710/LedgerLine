package com.hardik.billing.payment.service;

import com.hardik.billing.common.BillingException;
import com.hardik.billing.common.ErrorCode;
import com.hardik.billing.invoice.Invoice;
import com.hardik.billing.invoice.InvoiceRepository;
import com.hardik.billing.invoice.InvoiceStatus;
import com.hardik.billing.ledger.LedgerKind;
import com.hardik.billing.ledger.service.LedgerService;
import com.hardik.billing.payment.Payment;
import com.hardik.billing.payment.PaymentProcessorType;
import com.hardik.billing.payment.PaymentRepository;
import com.hardik.billing.payment.PaymentStatus;
import com.hardik.billing.payment.PaymentStatusHistory;
import com.hardik.billing.payment.PaymentStatusHistoryRepository;
import com.hardik.billing.payment.dto.PaymentResponse;
import com.hardik.billing.payment.statemachine.PaymentStateMachine;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentTransactions {

    private static final Logger log = LoggerFactory.getLogger(PaymentTransactions.class);

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final PaymentStatusHistoryRepository history;
    private final LedgerService ledger;

    public PaymentTransactions(InvoiceRepository invoices, PaymentRepository payments,
                               PaymentStatusHistoryRepository history, LedgerService ledger) {
        this.invoices = invoices;
        this.payments = payments;
        this.history = history;
        this.ledger = ledger;
    }

    @Transactional
    public PaymentResponse create(UUID invoiceId, String token, PaymentProcessorType processor) {
        Invoice invoice = invoices.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new BillingException(ErrorCode.NOT_FOUND, "Invoice was not found."));
        if (invoice.getStatus() != InvoiceStatus.OPEN) {
            throw new BillingException(ErrorCode.INVALID_STATE_TRANSITION, "Invoice must be OPEN to create a payment.");
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var payment = new Payment(invoiceId, invoice.getTotalAmount(), invoice.getCurrency(), processor, token, now);
        try {
            // Flush to check the active-payment index and insert the payment before its history row.
            payments.saveAndFlush(payment);
        } catch (DataIntegrityViolationException exception) {
            if (exception.getCause() instanceof ConstraintViolationException violation
                    && "one_active_payment_per_invoice".equals(violation.getConstraintName())) {
                throw new BillingException(ErrorCode.PAYMENT_ALREADY_ACTIVE, "Invoice already has an active payment.");
            }
            throw exception;
        }
        history.save(new PaymentStatusHistory(payment.getId(), null, PaymentStatus.CREATED,
                "created", PaymentStatusHistory.Actor.API, now));
        return PaymentResponse.from(payment);
    }

    @Transactional
    public PaymentResponse applyTransition(Payment snapshot, PaymentStatus target,
                                           String processorReference, String failureReason, String reason,
                                           PaymentStatusHistory.Actor actor) {
        UUID paymentId = snapshot.getId();
        LedgerKind kind = postingKind(target);
        Invoice invoice = lockInvoiceForPosting(snapshot.getInvoiceId(), kind);
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> new BillingException(ErrorCode.NOT_FOUND, "Payment was not found."));
        if (!payment.getVersion().equals(snapshot.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Payment.class, paymentId);
        }

        persistTransition(payment, invoice, kind, target, processorReference, failureReason, reason, actor, now());
        return PaymentResponse.from(payment);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean applyWebhook(UUID paymentId, String reference, PaymentStatus target, String failureReason,
                                String eventType) {
        var location = paymentId == null ? Optional.<PaymentRepository.Location>empty()
                : payments.findLocationById(paymentId);
        if (location.isEmpty() && reference != null) {
            location = payments.findLocationByProcessorReference(reference);
        }
        if (location.isEmpty()) {
            return false;
        }

        var match = location.get();
        LedgerKind kind = postingKind(target);
        Invoice invoice = lockInvoiceForPosting(match.getInvoiceId(), kind);
        Payment payment = payments.findByIdForUpdate(match.getId())
                .orElseThrow(() -> new IllegalStateException("Matched webhook payment disappeared."));
        Instant now = now();
        payment.attachProcessorReferenceIfMissing(reference, now);
        if (payment.getStatus() == target) {
            return true;
        }
        if (!PaymentStateMachine.canTransition(payment.getStatus(), target)) {
            log.warn("Ignoring Stripe event type {} for payment {} in status {}.", eventType,
                    payment.getId(), payment.getStatus());
            return true;
        }
        if (kind != null && ledger.hasPosting(kind, payment.getId())) {
            log.warn("Ignoring Stripe event type {} for payment {} with an existing ledger posting.",
                    eventType, payment.getId());
            return true;
        }

        persistTransition(payment, invoice, kind, target, null, failureReason, eventType,
                PaymentStatusHistory.Actor.WEBHOOK, now);
        return true;
    }

    private Invoice lockInvoiceForPosting(UUID invoiceId, LedgerKind kind) {
        if (kind == null) {
            return null;
        }
        // Invoice-changing writes take the invoice lock before the payment lock.
        return invoices.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new BillingException(ErrorCode.NOT_FOUND, "Invoice was not found."));
    }

    private static LedgerKind postingKind(PaymentStatus target) {
        return switch (target) {
            case CAPTURED -> LedgerKind.PAYMENT_CAPTURED;
            case REFUNDED -> LedgerKind.PAYMENT_REFUNDED;
            default -> null;
        };
    }

    private void persistTransition(Payment payment, Invoice invoice, LedgerKind kind, PaymentStatus target,
                                   String processorReference, String failureReason, String reason,
                                   PaymentStatusHistory.Actor actor, Instant now) {
        PaymentStatus previous = payment.getStatus();
        payment.transitionTo(target, processorReference, failureReason, now);
        if (invoice != null) {
            InvoiceStatus invoiceTarget = target == PaymentStatus.CAPTURED ? InvoiceStatus.PAID : InvoiceStatus.REFUNDED;
            invoice.transitionTo(invoiceTarget, now);
        }
        history.save(new PaymentStatusHistory(payment.getId(), previous, target, reason, actor, now));
        if (invoice != null) {
            ledger.post(kind, payment.getId(), payment.getCurrency(), kind.entries(payment.getAmount()));
        }
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
