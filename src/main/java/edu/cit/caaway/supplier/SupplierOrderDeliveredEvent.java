package edu.cit.caaway.supplier;

/** A reorder arrived: {@code units} single units of {@code productId} can be added to stock. */
public record SupplierOrderDeliveredEvent(Long reorderId, String productId, int units) {
}
