package edu.cit.caaway.shop;

import edu.cit.caaway.inventory.InventoryItem;
import edu.cit.caaway.inventory.InventoryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:5173")
public class OrderController {

    private final OrderService orderService;
    private final InventoryService inventoryService;

    public OrderController(OrderService orderService, InventoryService inventoryService) {
        this.orderService = orderService;
        this.inventoryService = inventoryService;
    }

    @GetMapping("/inventory")
    public List<InventoryItem> getInventory() {
        return inventoryService.getAllItems();
    }

    @PostMapping("/orders")
    public OrderService.OrderResponse placeOrder(@RequestBody OrderRequest request) {
        return orderService.placeOrder(request.productId(), request.quantity());
    }

    public record OrderRequest(String productId, int quantity) {}
}