package edu.cit.caaway.supplier;

public record SupplierOrderRequest(
        String localProductId,
        int requiredQuantity
) {}