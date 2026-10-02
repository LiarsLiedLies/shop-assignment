package edu.cit.caaway.inventory;

import edu.cit.caaway.supplier.ReorderResult;
import edu.cit.caaway.supplier.SupplierGateway;
import edu.cit.caaway.supplier.SupplierOrderCancelledEvent;
import edu.cit.caaway.supplier.SupplierOrderDeliveredEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class InventoryEventListener {

    // Units asked for whenever a product runs low.
    private static final int REORDER_UNITS = 24;

    private final SupplierGateway supplierGateway;
    private final InventoryService inventoryService;

    public InventoryEventListener(SupplierGateway supplierGateway, InventoryService inventoryService) {
        this.supplierGateway = supplierGateway;
        this.inventoryService = inventoryService;
    }

    // Low-Stock Auto-Reorder Rule
    @EventListener
    public void handleLowStock(LowStockEvent event) {
        ReorderResult result = supplierGateway.requestReorder(event.productId(), REORDER_UNITS);
        System.out.println(">>> REORDER for " + event.productId() + " (stock " + event.remainingStock() + "): "
                + result.status() + ", " + result.unitsOrdered() + " units, " + result.message());
    }

    @EventListener
    public void handleSupplierDelivery(SupplierOrderDeliveredEvent event) {
        inventoryService.restock(event.productId(), event.units());
        System.out.println(">>> RESTOCKED " + event.units() + " units of " + event.productId()
                + " from reorder " + event.reorderId());
    }

    // The goods will never arrive, so ask again if the product is still low.
    @EventListener
    public void handleSupplierCancellation(SupplierOrderCancelledEvent event) {
        if (inventoryService.isLowStock(event.productId())) {
            ReorderResult result = supplierGateway.requestReorder(event.productId(), REORDER_UNITS);
            System.out.println(">>> REORDER for " + event.productId() + " after supplier cancellation: "
                    + result.status() + ", " + result.message());
        }
    }
}
