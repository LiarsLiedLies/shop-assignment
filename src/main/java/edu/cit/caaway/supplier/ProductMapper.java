package edu.cit.caaway.supplier;

import org.springframework.stereotype.Component;

@Component
class ProductMapper {

    record Mapping(String legacySku, int packSize) {}

    public Mapping getMapping(String localProductId) {
        return switch (localProductId) {
            case "PROD-001" -> new Mapping("LPB-9626", 24); // USB-C Cable 1M
            case "PROD-002" -> new Mapping("LPB-1517", 6);  // Wireless Mouse
            case "PROD-003" -> new Mapping("LPB-1455", 24); // Keyboard Mech TKL
            default -> throw new IllegalArgumentException("Unknown product: " + localProductId);
        };
    }

    public int calculateCases(int requiredUnits, int packSize) {
        return (int) Math.ceil((double) requiredUnits / packSize);
    }
}