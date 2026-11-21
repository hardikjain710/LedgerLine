package com.hardik.billing.payment.service;

import com.hardik.billing.common.BillingException;
import com.hardik.billing.common.ErrorCode;
import com.hardik.billing.payment.Payment;
import com.hardik.billing.payment.PaymentProcessorType;
import com.hardik.billing.payment.PaymentRepository;
import com.hardik.billing.payment.PaymentStatus;
import com.hardik.billing.payment.PaymentStatusHistory;
import com.hardik.billing.payment.PaymentStatusHistoryRepository;
import com.hardik.billing.payment.dto.CreatePaymentRequest;
import com.hardik.billing.payment.dto.PaymentHistoryResponse;
import com.hardik.billing.payment.dto.PaymentResponse;
import com.hardik.billing.payment.processor.PaymentProcessor;
import com.hardik.billing.payment.processor.ProcessorResult;
import com.hardik.billing.payment.statemachine.PaymentStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final PaymentStatusHistoryRepository history;
    private final PaymentProcessor processor;
    private final PaymentTransactions transactions;
    private final PaymentProcessorType processorType;

    public PaymentService(PaymentRepository payments, PaymentStatusHistoryRepository history,
                          PaymentProcessor processor, PaymentTransactions transactions,
                          @Value("${billing.processor:simulated}") String processorName) {
        this.payments = payments;
        this.history = history;
        this.processor = processor;
        this.transactions = transactions;
        this.processorType = PaymentProcessorType.valueOf(processorName.toUpperCase(Locale.ROOT));
    }

    public PaymentResponse create(UUID invoiceId, CreatePaymentRequest request) {
        return transactions.create(invoiceId, request.paymentMethodToken(), processorType);
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(UUID paymentId) {
        return PaymentResponse.from(findPayment(paymentId));
    }

    @Transactional(readOnly = true)
    public List<PaymentHistoryResponse> history(UUID paymentId) {
        if (!payments.existsById(paymentId)) {
            throw new BillingException(ErrorCode.NOT_FOUND, "Payment was not found.");
        }
        return history.findByPaymentIdOrderByIdAsc(paymentId).stream().map(PaymentHistoryResponse::from).toList();
    }

    public PaymentResponse authorize(UUID paymentId) {
        return perform(paymentId, Action.AUTHORIZE);
    }

    public PaymentResponse capture(UUID paymentId) {
        return perform(paymentId, Action.CAPTURE);
    }

    public PaymentResponse voidPayment(UUID paymentId) {
        return perform(paymentId, Action.VOID);
    }

    public PaymentResponse refund(UUID paymentId) {
        return perform(paymentId, Action.REFUND);
    }

    public boolean applyWebhook(UUID paymentId, String reference, PaymentStatus target, String failureReason,
                                String eventType) {
        return transactions.applyWebhook(paymentId, reference, target, failureReason, eventType);
    }

    private PaymentResponse perform(UUID paymentId, Action action) {
        Payment payment = findPayment(paymentId);
        PaymentStateMachine.transition(payment.getStatus(), action.target);

        // The repository read has finished. The processor call runs before the write transaction starts.
        boolean callProcessor = action != Action.VOID || payment.getStatus() == PaymentStatus.AUTHORIZED;
        ProcessorResult result = callProcessor ? callProcessor(action, payment) : ProcessorResult.succeeded(null);

        boolean declined = action == Action.AUTHORIZE && !result.success();
        if (!result.success() && !declined) {
            throw new BillingException(ErrorCode.PROCESSOR_ERROR, "Payment processor rejected the action: "
                    + result.failureReason());
        }
        PaymentStatus target = declined ? PaymentStatus.FAILED : action.target;
        String reason = declined ? "authorization declined: " + result.failureReason() : action.reason;

        String newReference = action == Action.AUTHORIZE && result.success() ? result.processorReference() : null;
        try {
            return transactions.applyTransition(payment, target, newReference,
                    result.failureReason(), reason, PaymentStatusHistory.Actor.API);
        } catch (RuntimeException exception) {
            if (callProcessor && result.success()) {
                String reference = newReference != null ? newReference : payment.getProcessorReference();
                log.error("Failed to save payment after successful processor call. paymentId={} processorReference={} error={}",
                        paymentId, reference, exception.getClass().getSimpleName());
            }
            throw exception;
        }
    }

    private ProcessorResult callProcessor(Action action, Payment payment) {
        String paymentId = payment.getId().toString();
        String reference = payment.getProcessorReference();
        return switch (action) {
            case AUTHORIZE -> processor.authorize(payment.getPaymentMethodToken(), payment.getAmount(),
                    payment.getCurrency(), paymentId);
            case CAPTURE -> processor.capture(reference, payment.getAmount(), paymentId);
            case VOID -> processor.voidAuthorization(reference, paymentId);
            case REFUND -> processor.refund(reference, payment.getAmount(), paymentId);
        };
    }

    private Payment findPayment(UUID paymentId) {
        return payments.findById(paymentId)
                .orElseThrow(() -> new BillingException(ErrorCode.NOT_FOUND, "Payment was not found."));
    }

    private enum Action {
        AUTHORIZE(PaymentStatus.AUTHORIZED, "authorized"),
        CAPTURE(PaymentStatus.CAPTURED, "captured"),
        VOID(PaymentStatus.VOIDED, "voided"),
        REFUND(PaymentStatus.REFUNDED, "refunded");

        private final PaymentStatus target;
        private final String reason;

        Action(PaymentStatus target, String reason) {
            this.target = target;
            this.reason = reason;
        }
    }
}
