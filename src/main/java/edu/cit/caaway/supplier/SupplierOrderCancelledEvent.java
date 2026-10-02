package edu.cit.caaway.supplier;

/** The supplier cancelled a reorder: {@code units} single units of {@code productId} will never arrive. */
public record SupplierOrderCancelledEvent(Long reorderId, String productId, int units) {
}
