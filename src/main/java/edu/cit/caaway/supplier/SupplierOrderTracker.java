package edu.cit.caaway.supplier;

import edu.cit.caaway.supplier.LegacySupplyXml.PurchaseOrderDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Asks LegacySupply how the open purchase orders are doing. Orders that reached a final status are
 * never asked about again, which keeps us inside the request quota.
 */
@Component
class SupplierOrderTracker {

    private static final Logger log = LoggerFactory.getLogger(SupplierOrderTracker.class);

    private static final List<ReorderStatus> IN_TRANSIT =
            List.of(ReorderStatus.PLACED, ReorderStatus.PICKING, ReorderStatus.SHIPPED);
    private static final Duration PAUSE_WHEN_UNAVAILABLE = Duration.ofSeconds(30);
    private static final Duration PAUSE_WHEN_RATE_LIMITED = Duration.ofSeconds(90);
    // Orders with a status we do not understand are only re-checked every 8th round.
    private static final int REVIEW_EVERY_ROUNDS = 8;

    private final SupplierOrderRepository orders;
    private final LegacySupplyClient client;
    private final SupplierOrderLifecycle lifecycle;

    private Instant pausedUntil = Instant.MIN;
    private long round = 0;

    SupplierOrderTracker(SupplierOrderRepository orders, LegacySupplyClient client, SupplierOrderLifecycle lifecycle) {
        this.orders = orders;
        this.client = client;
        this.lifecycle = lifecycle;
    }

    @Scheduled(fixedDelayString = "${supplier.tracking-interval-ms:15000}", initialDelay = 8000)
    void checkOpenOrders() {
        if (Instant.now().isBefore(pausedUntil)) {
            return;
        }
        round++;
        if (!check(orders.findByStatusInOrderByIdAsc(IN_TRANSIT))) {
            return;
        }
        if (round % REVIEW_EVERY_ROUNDS == 0) {
            check(orders.findByStatusOrderByIdAsc(ReorderStatus.NEEDS_REVIEW));
        }
    }

    /** Returns false when LegacySupply became unavailable and the round was abandoned. */
    private boolean check(List<SupplierOrder> toCheck) {
        for (SupplierOrder order : toCheck) {
            try {
                PurchaseOrderDoc status = client.orderStatus(order.getPoNumber());
                lifecycle.recordSupplierStatus(order.getId(), status.statusCode());
            } catch (SupplierUnavailableException e) {
                Duration pause = e.isRateLimited() ? PAUSE_WHEN_RATE_LIMITED : PAUSE_WHEN_UNAVAILABLE;
                pausedUntil = Instant.now().plus(pause);
                log.warn("Tracking paused for {}s: {}", pause.toSeconds(), e.getMessage());
                return false;
            } catch (RuntimeException e) {
                // One odd answer must not stop the other orders from being tracked.
                log.error("Could not track reorder {} ({}): {}", order.getBuyerRef(), order.getPoNumber(), e.getMessage());
            }
        }
        return true;
    }
}
