package edu.cit.caaway.inventory;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
class DataInitializer implements CommandLineRunner {

    private final InventoryRepository inventoryRepository;

    public DataInitializer(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    @Override
    public void run(String... args) {
        if (inventoryRepository.count() == 0) {
            // Re-seed your initial inventory products with stock = 10
            inventoryRepository.save(new InventoryItem("PROD-001", "USB-C Cable 1M", 10));
            inventoryRepository.save(new InventoryItem("PROD-002", "Wireless Mouse", 10));
            inventoryRepository.save(new InventoryItem("PROD-003", "Mechanical Keyboard", 10));
            System.out.println(">>> Initial inventory data successfully seeded!");
        }
    }
}