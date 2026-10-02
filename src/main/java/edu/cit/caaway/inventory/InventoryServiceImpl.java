package edu.cit.caaway.inventory;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
class InventoryServiceImpl implements InventoryService { // Package-private!

    private static final int LOW_STOCK_THRESHOLD = 5;
    private final InventoryRepository inventoryRepository;
    private final ApplicationEventPublisher eventPublisher;

    InventoryServiceImpl(InventoryRepository inventoryRepository, ApplicationEventPublisher eventPublisher) {
        this.inventoryRepository = inventoryRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public List<InventoryItem> getAllInventory() {
        return inventoryRepository.findAll();
    }

    @Override
    public boolean validateStock(String productId, int quantity) {
        return inventoryRepository.findById(productId)
                .map(item -> item.getStock() >= quantity)
                .orElse(false);
    }

    @Override
    public int getAvailable(String productId) {
        return inventoryRepository.findById(productId).map(InventoryItem::getStock).orElse(0);
    }

    @Override
    public boolean isLowStock(String productId) {
        return inventoryRepository.findById(productId)
                .map(item -> item.getStock() < LOW_STOCK_THRESHOLD)
                .orElse(false);
    }

    @Override
    @Transactional
    public void reserveStock(String productId, int quantity) {
        InventoryItem item = inventoryRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));

        // Never sell what is not there, even if two orders are placed at the same moment.
        if (item.getStock() < quantity) {
            throw new IllegalStateException("Insufficient stock for product: " + productId);
        }
        item.setStock(item.getStock() - quantity);
        inventoryRepository.save(item);
        eventPublisher.publishEvent(new StockChangedEvent(productId, item.getStock()));

        // Emit low-stock event if stock falls below threshold
        if (item.getStock() < LOW_STOCK_THRESHOLD) {
            eventPublisher.publishEvent(new LowStockEvent(productId, item.getStock()));
        }
    }

    @Override
    @Transactional
    public void restock(String productId, int quantity) {
        InventoryItem item = inventoryRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));
        item.setStock(item.getStock() + quantity);
        inventoryRepository.save(item);
        eventPublisher.publishEvent(new StockChangedEvent(productId, item.getStock()));
    }
}