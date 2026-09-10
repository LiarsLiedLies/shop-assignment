package edu.cit.caaway.shop;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "orders")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long orderId;
    private String productId;
    private int quantity;
    private String status;
    private String reason;
    private LocalDateTime createdAt = LocalDateTime.now();

    public Order() {}

    public Order(String productId, int quantity, String status, String reason) {
        this.productId = productId;
        this.quantity = quantity;
        this.status = status;
        this.reason = reason;
    }

    public Long getOrderId() { return orderId; }
    public String getProductId() { return productId; }
    public int getQuantity() { return quantity; }
    public String getStatus() { return status; }
    public String getReason() { return reason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}