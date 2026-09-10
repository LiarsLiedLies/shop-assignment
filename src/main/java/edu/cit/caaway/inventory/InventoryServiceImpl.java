package edu.cit.caaway.inventory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

@Service
class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository inventoryRepository;

    InventoryServiceImpl(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    @Override
    public Optional<InventoryItem> getItem(String productId) {
        return inventoryRepository.findById(productId);
    }

    @Override
    public List<InventoryItem> getAllItems() {
        return inventoryRepository.findAll();
    }

    @Override
    @Transactional
    public boolean reserve(String productId, int quantity) {
        Optional<InventoryItem> itemOpt = inventoryRepository.findById(productId);
        if (itemOpt.isPresent()) {
            InventoryItem item = itemOpt.get();
            if (item.getStock() >= quantity) {
                item.setStock(item.getStock() - quantity);
                inventoryRepository.save(item);
                return true;
            }
        }
        return false;
    }
}