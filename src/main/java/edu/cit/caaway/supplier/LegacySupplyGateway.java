package edu.cit.caaway.supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Records reorders in supplier_orders. It makes no network call: {@link SupplierOrderDispatcher}
 * sends what is stored here, so a reorder exists exactly when the stock change that caused it was
 * saved, whatever state LegacySupply is in.
 */
@Service
class LegacySupplyGateway implements SupplierGateway {

    private static final Logger log = LoggerFactory.getLogger(LegacySupplyGateway.class);

    private static final List<ReorderStatus> OPEN =
            List.of(ReorderStatus.PENDING, ReorderStatus.PLACED, ReorderStatus.PICKING, ReorderStatus.SHIPPED);

    private static final List<ReorderStatus> IN_TRANSIT =
            List.of(ReorderStatus.PLACED, ReorderStatus.PICKING, ReorderStatus.SHIPPED);

    private final SupplierCatalog catalog;
    private final SupplierOrderRepository orders;

    LegacySupplyGateway(SupplierCatalog catalog, SupplierOrderRepository orders) {
        this.catalog = catalog;
        this.orders = orders;
    }

    @Override
    public Optional<String> supplierItemFor(String productId) {
        return catalog.find(productId).map(SupplierCatalog.Item::supplierSku);
    }

    @Override
    public int unitsInTransit(String productId) {
        return orders.findByProductIdAndStatusInOrderByIdAsc(productId, IN_TRANSIT).stream()
                .mapToInt(SupplierOrder::getUnits).sum();
    }

    @Override
    public boolean hasOpenReorder(String productId) {
        return !orders.findByProductIdAndStatusInOrderByIdAsc(productId, OPEN).isEmpty();
    }

    @Override
    @Transactional
    public ReorderResult requestReorder(String productId, int unitsNeeded) {
        if (unitsNeeded <= 0) {
            return new ReorderResult(null, productId, 0, ReorderStatus.REJECTED, "Units needed must be positive");
        }
        Optional<SupplierCatalog.Item> item = catalog.find(productId);
        if (item.isEmpty()) {
            log.warn("No supplier item for product {}; reorder not recorded", productId);
            return new ReorderResult(null, productId, 0, ReorderStatus.REJECTED, "Supplier does not carry this product");
        }

        // Stock stays low until the goods arrive, so the rule fires again and again. One open reorder is enough.
        List<SupplierOrder> open = orders.findByProductIdAndStatusInOrderByIdAsc(productId, OPEN);
        if (!open.isEmpty()) {
            SupplierOrder existing = open.get(0);
            return new ReorderResult(existing.getId(), productId, existing.getUnits(), existing.getStatus(),
                    "A reorder for this product is already open");
        }

        int cases = item.get().casesFor(unitsNeeded);
        SupplierOrder order = orders.saveAndFlush(
                new SupplierOrder(productId, UUID.randomUUID().toString(), cases, item.get().unitsIn(cases)));
        order.setBuyerRef("RO-" + order.getId());
        log.info("Reorder {} recorded: {} units of {} needed, {} will be ordered",
                order.getBuyerRef(), unitsNeeded, productId, order.getUnits());
        return new ReorderResult(order.getId(), productId, order.getUnits(), order.getStatus(), "Reorder recorded");
    }
}
