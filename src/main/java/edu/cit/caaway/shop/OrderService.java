package edu.cit.caaway.shop;

import edu.cit.caaway.inventory.InventoryService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final InventoryService inventoryService; // Interface injection
    private final ApplicationEventPublisher eventPublisher;

    public OrderService(OrderRepository orderRepository, InventoryService inventoryService, ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.inventoryService = inventoryService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Order placeOrder(List<OrderItemRequest> itemRequests) {
        Order order = new Order();

        // 1. All-or-Nothing Pre-Validation
        for (OrderItemRequest req : itemRequests) {
            if (!inventoryService.validateStock(req.productId(), req.quantity())) {
                order.setStatus("REJECTED");
                order.setReason("Insufficient stock for product: " + req.productId());
                Order savedOrder = orderRepository.save(order);

                eventPublisher.publishEvent(new OrderRejectedEvent(savedOrder.getId(), savedOrder.getReason()));
                return savedOrder;
            }
        }

        // 2. Reserve Stock
        for (OrderItemRequest req : itemRequests) {
            inventoryService.reserveStock(req.productId(), req.quantity());
            OrderItem item = new OrderItem(order, req.productId(), req.quantity());
            order.getItems().add(item);
        }

        order.setStatus("CONFIRMED");
        Order savedOrder = orderRepository.save(order);

        eventPublisher.publishEvent(new OrderPlacedEvent(savedOrder.getId(), "Multi-item order placed."));
        return savedOrder;
    }

    @Transactional
    public Order cancelOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        if ("CANCELLED".equals(order.getStatus())) {
            throw new RuntimeException("Order is already cancelled");
        }

        for (OrderItem item : order.getItems()) {
            inventoryService.restock(item.getProductId(), item.getQuantity());
        }

        order.setStatus("CANCELLED");
        return orderRepository.save(order);
    }
}