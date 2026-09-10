package edu.cit.caaway.shop;

import edu.cit.caaway.inventory.InventoryItem;
import edu.cit.caaway.inventory.InventoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class OrderService {

    private final InventoryService inventoryService;
    private final OrderRepository orderRepository;

    public OrderService(InventoryService inventoryService, OrderRepository orderRepository) {
        this.inventoryService = inventoryService;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public OrderResponse placeOrder(String productId, int quantity) {
        Optional<InventoryItem> itemOpt = inventoryService.getItem(productId);

        if (itemOpt.isEmpty()) {
            Order order = new Order(productId, quantity, "REJECTED", "Product not found");
            orderRepository.save(order);
            return new OrderResponse("REJECTED", "Product not found", null);
        }

        boolean reserved = inventoryService.reserve(productId, quantity);

        if (reserved) {
            Order order = new Order(productId, quantity, "CONFIRMED", "Order placed successfully");
            orderRepository.save(order);
            InventoryItem updatedItem = inventoryService.getItem(productId).orElse(null);
            return new OrderResponse("CONFIRMED", "Order placed successfully", updatedItem);
        } else {
            Order order = new Order(productId, quantity, "REJECTED", "Insufficient stock");
            orderRepository.save(order);
            InventoryItem currentItem = itemOpt.get();
            return new OrderResponse("REJECTED", "Insufficient stock", currentItem);
        }
    }

    public record OrderResponse(String status, String reason, InventoryItem inventory) {}
}