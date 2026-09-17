package edu.cit.caaway.shop;

import edu.cit.caaway.inventory.InventoryItem;
import edu.cit.caaway.inventory.InventoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class OrderController {

    private final OrderService orderService;
    private final InventoryService inventoryService;
    private final OrderRepository orderRepository;

    public OrderController(OrderService orderService, InventoryService inventoryService, OrderRepository orderRepository) {
        this.orderService = orderService;
        this.inventoryService = inventoryService;
        this.orderRepository = orderRepository;
    }

    // GET /api/inventory - Live inventory query
    @GetMapping("/inventory")
    public List<InventoryItem> getInventory() {
        return inventoryService.getAllInventory();
    }

    // GET /api/orders - Order history query
    @GetMapping("/orders")
    @Transactional(readOnly = true)
    public List<Order> getOrders() {
        return orderRepository.findAll();
    }

    // POST /api/orders - Multi-item order placement
    @PostMapping("/orders")
    public ResponseEntity<Order> placeOrder(@RequestBody List<OrderItemRequest> itemRequests) {
        Order order = orderService.placeOrder(itemRequests);
        return ResponseEntity.ok(order);
    }

    // DELETE /api/orders/{orderId} - Cancel order and restock
    @DeleteMapping("/orders/{orderId}")
    public ResponseEntity<Order> cancelOrder(@PathVariable Long orderId) {
        Order cancelledOrder = orderService.cancelOrder(orderId);
        return ResponseEntity.ok(cancelledOrder);
    }

    // POST /api/orders/{orderId}/cancel - Alternative cancellation endpoint
    @PostMapping("/orders/{orderId}/cancel")
    public ResponseEntity<Order> cancelOrderPost(@PathVariable Long orderId) {
        Order cancelledOrder = orderService.cancelOrder(orderId);
        return ResponseEntity.ok(cancelledOrder);
    }
}