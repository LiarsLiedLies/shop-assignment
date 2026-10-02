package edu.cit.caaway.supplier;

/** A reorder is stuck and needs a person to look at it. */
public record SupplierOrderNeedsAttentionEvent(Long reorderId, String productId, String reason) {
}
