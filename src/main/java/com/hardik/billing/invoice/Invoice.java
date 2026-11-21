package com.hardik.billing.invoice;

import com.hardik.billing.invoice.statemachine.InvoiceStateMachine;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "invoices")
public class Invoice {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvoiceStatus status;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private long totalAmount;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant finalizedAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.PERSIST)
    @OrderBy("lineNumber ASC")
    @BatchSize(size = 32)
    private List<InvoiceLineItem> lineItems = new ArrayList<>();

    protected Invoice() {
    }

    public Invoice(UUID customerId, String currency, long totalAmount, Instant now, List<InvoiceLineItem> lineItems) {
        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.status = InvoiceStatus.DRAFT;
        this.currency = currency;
        this.totalAmount = totalAmount;
        this.createdAt = now;
        this.updatedAt = now;
        for (InvoiceLineItem item : lineItems) {
            item.attachTo(this);
            this.lineItems.add(item);
        }
    }

    public void transitionTo(InvoiceStatus target, Instant now) {
        this.status = InvoiceStateMachine.transition(status, target);
        this.updatedAt = now;
        if (target == InvoiceStatus.OPEN) {
            this.finalizedAt = now;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public long getTotalAmount() {
        return totalAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getFinalizedAt() {
        return finalizedAt;
    }

    public List<InvoiceLineItem> getLineItems() {
        return List.copyOf(lineItems);
    }
}
