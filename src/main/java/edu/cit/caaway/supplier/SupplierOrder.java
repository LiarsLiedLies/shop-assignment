package edu.cit.caaway.supplier;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** One reorder and everything needed to send it again safely after a failure or a restart. */
@Entity
@Table(name = "supplier_orders")
class SupplierOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private String productId;

    // Assigned right after the first insert because it is built from the generated id.
    @Column(name = "buyer_ref", unique = true, length = 40)
    private String buyerRef;

    // Created once and stored, so every retry of this reorder carries the same value.
    @Column(name = "request_id", nullable = false, unique = true, length = 80)
    private String requestId;

    @Column(name = "po_number")
    private String poNumber;

    @Column(nullable = false)
    private int cases;

    @Column(nullable = false)
    private int units;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReorderStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected SupplierOrder() {
    }

    SupplierOrder(String productId, String requestId, int cases, int units) {
        this.productId = productId;
        this.requestId = requestId;
        this.cases = cases;
        this.units = units;
        this.status = ReorderStatus.PENDING;
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    Long getId() { return id; }
    String getProductId() { return productId; }
    String getBuyerRef() { return buyerRef; }
    String getRequestId() { return requestId; }
    String getPoNumber() { return poNumber; }
    int getCases() { return cases; }
    int getUnits() { return units; }
    ReorderStatus getStatus() { return status; }

    void setBuyerRef(String buyerRef) { this.buyerRef = buyerRef; }
    void setPoNumber(String poNumber) { this.poNumber = poNumber; }
    void setStatus(ReorderStatus status) { this.status = status; }
}
