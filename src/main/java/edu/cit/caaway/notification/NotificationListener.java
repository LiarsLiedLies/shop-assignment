package edu.cit.caaway.notification;

import edu.cit.caaway.inventory.LowStockEvent;
import edu.cit.caaway.shop.OrderPlacedEvent;
import edu.cit.caaway.shop.OrderRejectedEvent;
import edu.cit.caaway.supplier.SupplierOrderCancelledEvent;
import edu.cit.caaway.supplier.SupplierOrderDeliveredEvent;
import edu.cit.caaway.supplier.SupplierOrderNeedsAttentionEvent;
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

    @EventListener
    public void handleSupplierDelivery(SupplierOrderDeliveredEvent event) {
        notificationRepository.save(new Notification("REORDER DELIVERED: " + event.units() + " units of product " + event.productId() + " restocked"));
    }

    @EventListener
    public void handleSupplierCancellation(SupplierOrderCancelledEvent event) {
        notificationRepository.save(new Notification("REORDER CANCELLED BY SUPPLIER: " + event.units() + " units of product " + event.productId() + " will not arrive"));
    }

    @EventListener
    public void handleSupplierAttention(SupplierOrderNeedsAttentionEvent event) {
        notificationRepository.save(new Notification("REORDER NEEDS REVIEW: reorder " + event.reorderId() + " for product " + event.productId() + ", " + event.reason()));
    }
}