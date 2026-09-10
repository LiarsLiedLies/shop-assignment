package edu.cit.caaway.shop;

public record OrderResponse(String status, String reason, Object inventory) {}