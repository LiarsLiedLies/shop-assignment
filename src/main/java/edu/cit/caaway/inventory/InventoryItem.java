package edu.cit.caaway.inventory;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "inventory")
public class InventoryItem {
    @Id
    private String productId;
    private String name;
    private int stock;

    public InventoryItem() {}

    public InventoryItem(String productId, String name, int stock) {
        this.productId = productId;
        this.name = name;
        this.stock = stock;
    }

    public String getProductId() { return productId; }
    public String getName() { return name; }
    public int getStock() { return stock; }
    public void setStock(int stock) { this.stock = stock; }
}