package edu.cit.caaway.supplier;

/** Where a reorder is in its life, in the shop's own words. */
public enum ReorderStatus {
    /** Recorded by us, not yet accepted by the supplier. */
    PENDING,
    /** Accepted by the supplier. */
    PLACED,
    /** The supplier is preparing the goods. */
    PICKING,
    /** The goods are on their way. */
    SHIPPED,
    /** The goods arrived and were added to stock. */
    DELIVERED,
    /** The supplier cancelled the order; the goods will never arrive. */
    CANCELLED,
    /** The supplier refused the order; resending it unchanged would not help. */
    REJECTED,
    /** The supplier reported something we do not understand; a person has to look at it. */
    NEEDS_REVIEW;

    /** Goods are still expected for this reorder. */
    public boolean isOpen() {
        return this == PENDING || this == PLACED || this == PICKING || this == SHIPPED;
    }

    /** Nothing more will ever happen to this reorder. */
    public boolean isFinal() {
        return this == DELIVERED || this == CANCELLED || this == REJECTED;
    }
}
