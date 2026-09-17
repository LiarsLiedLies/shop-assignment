package edu.cit.caaway.inventory;

import java.util.List;
import java.util.Optional;

public interface InventoryService {
    List<InventoryItem> getAllInventory();
    boolean validateStock(String productId, int quantity);
    void reserveStock(String productId, int quantity);
    void restock(String productId, int quantity);
}