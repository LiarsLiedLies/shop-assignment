package edu.cit.caaway.supplier;

import java.util.Optional;

/**
 * The only way the rest of the shop talks to the supplier. Everything is expressed in the shop's
 * own terms: our product IDs and single units.
 */
public interface SupplierGateway {

    /**
     * Records a reorder for a product. The reorder is stored first and sent to the supplier in the
     * background, so this call never fails because the supplier is slow or down.
     *
     * @param productId   our Inventory product ID
     * @param unitsNeeded how many single units we need (the supplier may deliver more, never less)
     */
    ReorderResult requestReorder(String productId, int unitsNeeded);

    /** The supplier's item code for one of our products, empty when the supplier does not carry it. */
    Optional<String> supplierItemFor(String productId);

    /** Single units the supplier has accepted an order for and not yet delivered. */
    int unitsInTransit(String productId);

    /** True while any reorder for the product is still expected to bring goods, sent or not. */
    boolean hasOpenReorder(String productId);
}
