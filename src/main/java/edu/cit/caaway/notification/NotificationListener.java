package edu.cit.caaway.notification;

import edu.cit.caaway.inventory.LowStockEvent;
import edu.cit.caaway.shop.OrderPlacedEvent;
import edu.cit.caaway.shop.OrderRejectedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationListener {

    private final NotificationRepository notificationRepository;

    public NotificationListener(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @EventListener
    public void handleOrderPlaced(OrderPlacedEvent event) {
        notificationRepository.save(new Notification("Order #" + event.orderId() + " CONFIRMED: " + event.details()));
    }

    @EventListener
    public void handleOrderRejected(OrderRejectedEvent event) {
        notificationRepository.save(new Notification("Order #" + event.orderId() + " REJECTED: " + event.reason()));
    }

    @EventListener
    public void handleLowStock(LowStockEvent event) {
        notificationRepository.save(new Notification("REORDER NEEDED: Product " + event.productId() + " low stock (" + event.remainingStock() + " remaining)"));
    }
}