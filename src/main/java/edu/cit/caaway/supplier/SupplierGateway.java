package edu.cit.caaway.supplier;

public interface SupplierGateway {
    SupplierOrderResponse placeReorder(SupplierOrderRequest request);
}