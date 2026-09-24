package edu.cit.caaway.inventory;

import edu.cit.caaway.supplier.SupplierGateway;
import edu.cit.caaway.supplier.SupplierOrderRequest;
import edu.cit.caaway.supplier.SupplierOrderResponse;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class InventoryEventListener {

    private final SupplierGateway supplierGateway;

    public InventoryEventListener(SupplierGateway supplierGateway) {
        this.supplierGateway = supplierGateway;
    }

    @EventListener
    public void handleLowStock(LowStockEvent event) {
        System.out.println("\n==========================================");
        System.out.println(">>> REORDER NEEDED FOR PRODUCT: " + event.productId() + " (Stock Remaining: " + event.remainingStock() + ")");

        // Trigger ACL Reorder (Hardcoded 10 units threshold reorder for demo)
        SupplierOrderResponse response = supplierGateway.placeReorder(
                new SupplierOrderRequest(event.productId(), 10)
        );

        System.out.println(">>> ACL REORDER RESULT: " + response.message());
        System.out.println("==========================================\n");
    }
}