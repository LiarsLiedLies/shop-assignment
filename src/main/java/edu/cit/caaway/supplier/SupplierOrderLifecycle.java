package edu.cit.caaway.supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies what LegacySupply tells us to our own supplier_orders rows. Each method is one database
 * transaction, and events are published inside it, so a status change and the stock movement it
 * causes are saved together or not at all.
 */
@Component
class SupplierOrderLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SupplierOrderLifecycle.class);

    private final SupplierOrderRepository orders;
    private final ApplicationEventPublisher events;

    SupplierOrderLifecycle(SupplierOrderRepository orders, ApplicationEventPublisher events) {
        this.orders = orders;
        this.events = events;
    }

    /** LegacySupply accepted the order (or told us again about one it had already accepted). */
    @Transactional
    void recordAccepted(Long orderId, String poNumber, int statusCode) {
        SupplierOrder order = orders.findById(orderId).orElseThrow();
        order.setPoNumber(poNumber);
        log.info("Reorder {} accepted by supplier as {}", order.getBuyerRef(), poNumber);
        apply(order, statusCode);
    }

    @Transactional
    void recordSupplierStatus(Long orderId, int statusCode) {
        apply(orders.findById(orderId).orElseThrow(), statusCode);
    }

    /** LegacySupply refused the order itself; it stays on record but is never sent again. */
    @Transactional
    void recordRejected(Long orderId, String reason) {
        SupplierOrder order = orders.findById(orderId).orElseThrow();
        order.setStatus(ReorderStatus.REJECTED);
        log.error("Reorder {} rejected by supplier: {}", order.getBuyerRef(), reason);
        events.publishEvent(new SupplierOrderNeedsAttentionEvent(order.getId(), order.getProductId(),
                "supplier rejected the order: " + reason));
    }

    private void apply(SupplierOrder order, int statusCode) {
        ReorderStatus previous = order.getStatus();
        ReorderStatus next = LegacySupplyStatus.toReorderStatus(statusCode);
        if (previous.isFinal() || next == previous) {
            return;
        }
        order.setStatus(next);
        log.info("Reorder {} ({}): {} -> {}", order.getBuyerRef(), order.getPoNumber(), previous, next);

        switch (next) {
            case DELIVERED -> events.publishEvent(
                    new SupplierOrderDeliveredEvent(order.getId(), order.getProductId(), order.getUnits()));
            case CANCELLED -> events.publishEvent(
                    new SupplierOrderCancelledEvent(order.getId(), order.getProductId(), order.getUnits()));
            case NEEDS_REVIEW -> events.publishEvent(new SupplierOrderNeedsAttentionEvent(order.getId(),
                    order.getProductId(), "supplier reported an unexpected status (" + statusCode + ")"));
            default -> { }
        }
    }
}
