package edu.cit.caaway.shop;

public record OrderPlacedEvent(Long orderId, String details) {}