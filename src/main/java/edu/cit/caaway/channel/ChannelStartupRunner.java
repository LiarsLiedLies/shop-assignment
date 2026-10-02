package edu.cit.caaway.channel;

import edu.cit.caaway.inventory.InventoryItem;
import edu.cit.caaway.inventory.InventoryService;
import edu.cit.caaway.supplier.SupplierGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Opens the shop on Tiangge: publishes our products as listings, then their stock. Until that has
 * worked the feed is not read, and it is tried again by the poller if Tiangge was down at startup.
 */
@Component
@Order(2)
class ChannelStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ChannelStartupRunner.class);
    private static final int MAX_LISTINGS = 10;

    private final InventoryService inventoryService;
    private final SupplierGateway supplierGateway;
    private final TianggeClient client;
    private final ChannelStockSyncService stockSync;

    private volatile boolean live = false;

    ChannelStartupRunner(InventoryService inventoryService, SupplierGateway supplierGateway, TianggeClient client,
                         ChannelStockSyncService stockSync) {
        this.inventoryService = inventoryService;
        this.supplierGateway = supplierGateway;
        this.client = client;
        this.stockSync = stockSync;
    }

    @Override
    public void run(ApplicationArguments args) {
        goLiveIfNeeded();
    }

    /** Returns true once listings and stock are on Tiangge. */
    synchronized boolean goLiveIfNeeded() {
        if (live) {
            return true;
        }
        try {
            List<TianggeClient.Listing> listings = new ArrayList<>();
            for (InventoryItem item : inventoryService.getAllInventory()) {
                // Only products we can restock are sold on Tiangge.
                Optional<String> supplierItem = supplierGateway.supplierItemFor(item.getProductId());
                if (supplierItem.isPresent() && listings.size() < MAX_LISTINGS) {
                    listings.add(new TianggeClient.Listing(item.getProductId(), item.getName(), supplierItem.get()));
                }
            }
            if (listings.isEmpty()) {
                log.warn("No products with a supplier item; nothing to list on Tiangge");
                return false;
            }
            client.publishListings(listings);
            stockSync.publishAll(listings.stream().map(TianggeClient.Listing::productId).toList());
            live = true;
            log.info("Live on Tiangge with {} listings", listings.size());
        } catch (RuntimeException e) {
            log.warn("Could not go live on Tiangge yet: {}", e.getMessage());
        }
        return live;
    }
}
