package edu.cit.caaway.channel;

import edu.cit.caaway.inventory.InventoryService;
import edu.cit.caaway.inventory.StockChangedEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Keeps Tiangge's stock figures equal to ours. It reacts to Inventory's StockChangedEvent; there
 * is no timer. Tiangge wants the stock update after it has our answer about an order, so order
 * handling and stock publishing take turns through one lock.
 */
@Component
class ChannelStockSyncService {

    private static final Logger log = LoggerFactory.getLogger(ChannelStockSyncService.class);

    private final InventoryService inventoryService;
    private final TianggeClient client;

    // Fair, so a waiting stock update goes out between two orders instead of after a whole burst.
    private final ReentrantLock turn = new ReentrantLock(true);
    private final Set<String> changed = ConcurrentHashMap.newKeySet();
    private final Set<String> listed = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tiangge-stock");
        thread.setDaemon(true);
        return thread;
    });

    ChannelStockSyncService(InventoryService inventoryService, TianggeClient client) {
        this.inventoryService = inventoryService;
        this.client = client;
    }

    /** Fires once the stock change is really saved; a rolled-back order publishes nothing. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onStockChanged(StockChangedEvent event) {
        markChanged(Set.of(event.productId()));
    }

    void markChanged(Collection<String> productIds) {
        changed.addAll(productIds);
        worker.execute(this::publishChanged);
    }

    /** Called at go-live: these are the products Tiangge knows, publish all of them now. */
    void publishAll(Collection<String> productIds) {
        listed.clear();
        listed.addAll(productIds);
        client.publishStock(currentStock(productIds));
    }

    /** Runs order handling that must reach Tiangge before the stock change it causes. */
    <T> T beforeStockIsPublished(Supplier<T> orderHandling) {
        turn.lock();
        try {
            return orderHandling.get();
        } finally {
            turn.unlock();
        }
    }

    private void publishChanged() {
        turn.lock();
        Set<String> products = new HashSet<>();
        try {
            for (String productId : changed) {
                if (changed.remove(productId) && listed.contains(productId)) {
                    products.add(productId);
                }
            }
            if (products.isEmpty()) {
                return;
            }
            Map<String, Integer> stock = currentStock(products);
            client.publishStock(stock);
            log.info("Stock published to Tiangge: {}", stock);
        } catch (RuntimeException e) {
            // Tiangge is down or the database hiccuped: keep the products marked and try again shortly.
            log.warn("Stock update failed, will retry: {}", e.getMessage());
            changed.addAll(products);
            worker.schedule(this::publishChanged, 2, TimeUnit.SECONDS);
        } finally {
            turn.unlock();
        }
    }

    // Always reads the stock at the moment of sending, so Tiangge never gets an outdated number.
    private Map<String, Integer> currentStock(Collection<String> productIds) {
        Map<String, Integer> stock = new LinkedHashMap<>();
        for (String productId : productIds) {
            stock.put(productId, inventoryService.getAvailable(productId));
        }
        return stock;
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
