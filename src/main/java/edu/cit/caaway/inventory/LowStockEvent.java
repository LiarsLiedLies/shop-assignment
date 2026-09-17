package edu.cit.caaway.inventory;

public record LowStockEvent(String productId, int remainingStock) {}