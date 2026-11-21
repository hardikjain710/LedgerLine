package com.hardik.billing.payment;

import com.hardik.billing.payment.statemachine.PaymentStateMachine;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID invoiceId;

    @Column(nullable = false)
    private long amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentProcessorType processor;

    @Column(length = 255)
    private String processorReference;

    @Column(nullable = false, length = 255)
    private String paymentMethodToken;

    @Column(length = 255)
    private String failureReason;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Payment() {
    }

    public Payment(UUID invoiceId, long amount, String currency, PaymentProcessorType processor,
                   String paymentMethodToken, Instant now) {
        this.id = UUID.randomUUID();
        this.invoiceId = invoiceId;
        this.amount = amount;
        this.currency = currency;
        this.status = PaymentStatus.CREATED;
        this.processor = processor;
        this.paymentMethodToken = paymentMethodToken;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void transitionTo(PaymentStatus target, String processorReference, String failureReason, Instant now) {
        this.status = PaymentStateMachine.transition(status, target);
        if (processorReference != null) {
            this.processorReference = processorReference;
        }
        this.failureReason = failureReason;
        this.updatedAt = now;
    }

    public void attachProcessorReferenceIfMissing(String reference, Instant now) {
        if (processorReference == null && reference != null) {
            processorReference = reference;
            updatedAt = now;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getInvoiceId() {
        return invoiceId;
    }

    public long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public PaymentProcessorType getProcessor() {
        return processor;
    }

    public String getProcessorReference() {
        return processorReference;
    }

    public String getPaymentMethodToken() {
        return paymentMethodToken;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
