package edu.cit.caaway.supplier;

public record SupplierOrderResponse(
        boolean success,
        String poNumber,
        String status,
        String message
) {}