package edu.cit.caaway.shop;

public record OrderRejectedEvent(Long orderId, String reason) {}