package edu.cit.caaway.supplier;

/** Translates LegacySupply's numeric StatusCode into our own reorder status. */
final class LegacySupplyStatus {

    private LegacySupplyStatus() {
    }

    static ReorderStatus toReorderStatus(int statusCode) {
        return switch (statusCode) {
            case 10 -> ReorderStatus.PLACED;
            case 20 -> ReorderStatus.PICKING;
            case 30 -> ReorderStatus.SHIPPED;
            case 40 -> ReorderStatus.DELIVERED;
            // Not in the manual. Seen on PO-100036: the order was cancelled by LegacySupply.
            case 90 -> ReorderStatus.CANCELLED;
            // Anything else is never guessed at: no stock moves until a person has looked.
            default -> ReorderStatus.NEEDS_REVIEW;
        };
    }
}
