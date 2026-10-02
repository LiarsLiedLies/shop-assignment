package edu.cit.caaway.supplier;

import edu.cit.caaway.supplier.LegacySupplyXml.PurchaseOrderDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Sends PENDING reorders to LegacySupply. Rows stay PENDING until LegacySupply answers, so a
 * reorder survives outages and restarts and is always resent with its stored X-Request-Id.
 */
@Component
class SupplierOrderDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SupplierOrderDispatcher.class);

    private static final Duration FIRST_PAUSE = Duration.ofSeconds(5);
    private static final Duration LONGEST_PAUSE = Duration.ofSeconds(60);

    private final SupplierOrderRepository orders;
    private final SupplierCatalog catalog;
    private final LegacySupplyClient client;
    private final SupplierOrderLifecycle lifecycle;

    private Instant pausedUntil = Instant.MIN;
    private int failedRounds = 0;

    SupplierOrderDispatcher(SupplierOrderRepository orders, SupplierCatalog catalog, LegacySupplyClient client,
                            SupplierOrderLifecycle lifecycle) {
        this.orders = orders;
        this.catalog = catalog;
        this.client = client;
        this.lifecycle = lifecycle;
    }

    @Scheduled(fixedDelayString = "${supplier.dispatch-interval-ms:5000}", initialDelay = 3000)
    void sendPendingOrders() {
        if (Instant.now().isBefore(pausedUntil)) {
            return;
        }
        for (SupplierOrder order : orders.findByStatusOrderByIdAsc(ReorderStatus.PENDING)) {
            try {
                send(order);
                failedRounds = 0;
            } catch (SupplierUnavailableException e) {
                // LegacySupply is down or slow: leave everything PENDING and wait longer after each failed round.
                failedRounds++;
                Duration pause = FIRST_PAUSE.multipliedBy(1L << Math.min(failedRounds - 1, 4));
                if (pause.compareTo(LONGEST_PAUSE) > 0 || e.isRateLimited()) {
                    pause = LONGEST_PAUSE;
                }
                pausedUntil = Instant.now().plus(pause);
                log.warn("Reorder {} stays PENDING, next try in {}s: {}",
                        order.getBuyerRef(), pause.toSeconds(), e.getMessage());
                return;
            } catch (RuntimeException e) {
                log.error("Reorder {} could not be processed, will try again", order.getBuyerRef(), e);
            }
        }
    }

    private void send(SupplierOrder order) {
        Optional<SupplierCatalog.Item> item = catalog.find(order.getProductId());
        if (item.isEmpty()) {
            lifecycle.recordRejected(order.getId(), "product is no longer in the supplier catalog");
            return;
        }
        try {
            PurchaseOrderDoc ack = client.placeOrder(
                    item.get().supplierSku(), order.getCases(), order.getBuyerRef(), order.getRequestId());
            lifecycle.recordAccepted(order.getId(), ack.poNumber(), ack.statusCode());
        } catch (SupplierRejectedException e) {
            if (!adoptExistingOrder(order)) {
                lifecycle.recordRejected(order.getId(), e.getMessage());
            }
        }
    }

    /**
     * Second line of defence against duplicates: if LegacySupply refuses our request but already
     * holds an order under this BuyerRef, that order is ours and we take it over instead of sending a new one.
     */
    private boolean adoptExistingOrder(SupplierOrder order) {
        List<PurchaseOrderDoc> existing;
        try {
            existing = client.ordersByBuyerRef(order.getBuyerRef());
        } catch (SupplierRejectedException e) {
            return false;
        }
        if (existing.isEmpty()) {
            return false;
        }
        PurchaseOrderDoc found = existing.get(0);
        lifecycle.recordAccepted(order.getId(), found.poNumber(), found.statusCode());
        return true;
    }
}
