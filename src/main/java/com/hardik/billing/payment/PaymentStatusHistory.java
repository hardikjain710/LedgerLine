package com.hardik.billing.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_status_history")
public class PaymentStatusHistory {

    public enum Actor {
        API,
        WEBHOOK,
        SYSTEM
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus toStatus;

    @Column(length = 255)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Actor actor;

    @Column(nullable = false)
    private Instant createdAt;

    protected PaymentStatusHistory() {
    }

    public PaymentStatusHistory(UUID paymentId, PaymentStatus fromStatus, PaymentStatus toStatus,
                                String reason, Actor actor, Instant createdAt) {
        this.paymentId = paymentId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.actor = actor;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public PaymentStatus getFromStatus() {
        return fromStatus;
    }

    public PaymentStatus getToStatus() {
        return toStatus;
    }

    public String getReason() {
        return reason;
    }

    public Actor getActor() {
        return actor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
