package edu.cit.caaway.supplier;

/**
 * Outcome of asking for a reorder.
 *
 * @param reorderId    our own ID for the reorder, null when nothing was recorded
 * @param unitsOrdered single units that will arrive, which can be more than was asked for
 */
public record ReorderResult(Long reorderId, String productId, int unitsOrdered, ReorderStatus status, String message) {
}
