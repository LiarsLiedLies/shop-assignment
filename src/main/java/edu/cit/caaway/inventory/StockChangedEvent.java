package edu.cit.caaway.inventory;

/** The stock of a product changed, for whatever reason. {@code available} is the new quantity. */
public record StockChangedEvent(String productId, int available) {}
