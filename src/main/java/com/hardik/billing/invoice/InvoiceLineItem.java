package com.hardik.billing.invoice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "invoice_line_items")
public class InvoiceLineItem {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private long unitAmount;

    @Column(nullable = false)
    private int lineNumber;

    protected InvoiceLineItem() {
    }

    public InvoiceLineItem(String description, int quantity, long unitAmount, int lineNumber) {
        this.id = UUID.randomUUID();
        this.description = description;
        this.quantity = quantity;
        this.unitAmount = unitAmount;
        this.lineNumber = lineNumber;
    }

    void attachTo(Invoice invoice) {
        this.invoice = invoice;
    }

    public UUID getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public int getQuantity() {
        return quantity;
    }

    public long getUnitAmount() {
        return unitAmount;
    }

    public int getLineNumber() {
        return lineNumber;
    }
}
