package edu.cit.caaway.supplier;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "failed_reorders")
public class FailedReorder {

    @Id
    private String buyerRef;
    private String requestId;
    private String localProductId;
    private int requiredQuantity;
    private LocalDateTime createdAt;

    public FailedReorder() {}

    public FailedReorder(String buyerRef, String requestId, String localProductId, int requiredQuantity) {
        this.buyerRef = buyerRef;
        this.requestId = requestId;
        this.localProductId = localProductId;
        this.requiredQuantity = requiredQuantity;
        this.createdAt = LocalDateTime.now();
    }

    public String getBuyerRef() { return buyerRef; }
    public String getRequestId() { return requestId; }
    public String getLocalProductId() { return localProductId; }
    public int getRequiredQuantity() { return requiredQuantity; }
}