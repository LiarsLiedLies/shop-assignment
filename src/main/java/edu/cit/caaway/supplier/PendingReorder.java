package edu.cit.caaway.supplier;

import java.time.LocalDateTime;

public class PendingReorder {
    private final String localProductId;
    private final int requiredQuantity;
    private final String requestId;
    private final String buyerRef;
    private int attemptCount;
    private final LocalDateTime createdAt;

    public PendingReorder(String localProductId, int requiredQuantity, String requestId, String buyerRef) {
        this.localProductId = localProductId;
        this.requiredQuantity = requiredQuantity;
        this.requestId = requestId;
        this.buyerRef = buyerRef;
        this.attemptCount = 0;
        this.createdAt = LocalDateTime.now();
    }

    public String getLocalProductId() { return localProductId; }
    public int getRequiredQuantity() { return requiredQuantity; }
    public String getRequestId() { return requestId; }
    public String getBuyerRef() { return buyerRef; }
    public int getAttemptCount() { return attemptCount; }
    public void incrementAttempt() { this.attemptCount++; }
}