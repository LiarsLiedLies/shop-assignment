package edu.cit.caaway.supplier;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Translates our products into LegacySupply's item numbers and unit of measure.
 * Values come from GET /catalog for our client ID (see INTEGRATION.md).
 */
@Component
class SupplierCatalog {

    /** LegacySupply accepts a Qty of 1 to 99 cases per order. */
    static final int MAX_CASES = 99;

    record Item(String supplierSku, int packSize) {

        /** Cases needed to cover the units, rounded up because LegacySupply only sells whole cases. */
        int casesFor(int unitsNeeded) {
            int cases = (unitsNeeded + packSize - 1) / packSize;
            return Math.min(Math.max(cases, 1), MAX_CASES);
        }

        int unitsIn(int cases) {
            return cases * packSize;
        }
    }

    private static final Map<String, Item> ITEMS = Map.of(
            "PROD-001", new Item("LPB-9626", 24), // USB-C Cable 1M
            "PROD-002", new Item("LPB-1517", 6),  // Wireless Mouse
            "PROD-003", new Item("LPB-1455", 24)  // Mechanical Keyboard
    );

    Optional<Item> find(String productId) {
        return Optional.ofNullable(ITEMS.get(productId));
    }
}
