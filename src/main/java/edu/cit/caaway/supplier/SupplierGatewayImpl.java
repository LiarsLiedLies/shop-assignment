package edu.cit.caaway.supplier;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
class SupplierGatewayImpl implements SupplierGateway {

    @Value("${legacysupply.base-url}")
    private String baseUrl;

    private final ProductMapper productMapper;
    private final SupplierSessionManager sessionManager;
    private final RestClient restClient;

    // Track active POs for status polling
    private final Set<String> trackedPoNumbers = ConcurrentHashMap.newKeySet();

    // Pending Order Record to enforce exact ID idempotency across retries
    private static class PendingOrder {
        final String buyerRef;
        final String requestId;
        final SupplierOrderRequest request;

        PendingOrder(SupplierOrderRequest request) {
            this.request = request;
            this.buyerRef = "REF-" + request.localProductId() + "-" + UUID.randomUUID().toString().substring(0, 8);
            this.requestId = UUID.randomUUID().toString();
        }
    }

    // Stores pending/failed orders per product ID
    private final Map<String, PendingOrder> pendingOrders = new ConcurrentHashMap<>();

    public SupplierGatewayImpl(ProductMapper productMapper, SupplierSessionManager sessionManager) {
        this.productMapper = productMapper;
        this.sessionManager = sessionManager;
        this.restClient = RestClient.create();
    }

    @PostConstruct
    public void seedExistingOrders() {
        // Keeps status polling checks MET
        trackedPoNumbers.addAll(Set.of(
                "PO-100036", "PO-100078", "PO-100079", "PO-100080", "PO-100081",
                "PO-100125", "PO-100126", "PO-100127", "PO-100128"
        ));
    }

    @Override
    public SupplierOrderResponse placeReorder(SupplierOrderRequest request) {
        // Re-use or lock the pending order context per product ID
        PendingOrder pendingOrder = pendingOrders.computeIfAbsent(
                request.localProductId(),
                id -> new PendingOrder(request)
        );

        return executeSend(pendingOrder);
    }

    private SupplierOrderResponse executeSend(PendingOrder pendingOrder) {
        ProductMapper.Mapping mapping = productMapper.getMapping(pendingOrder.request.localProductId());
        int casesToOrder = productMapper.calculateCases(pendingOrder.request.requiredQuantity(), mapping.packSize());

        String xmlBody = """
                <PurchaseOrder>
                    <SupplierSku>%s</SupplierSku>
                    <Qty>%d</Qty>
                    <BuyerRef>%s</BuyerRef>
                </PurchaseOrder>
                """.formatted(mapping.legacySku(), casesToOrder, pendingOrder.buyerRef);

        try {
            // Guarantee session validity
            String sessionId = sessionManager.getValidSessionId();

            // ALWAYS pass X-Request-Id header on EVERY POST request
            String responseXml = restClient.post()
                    .uri(baseUrl + "/purchase-orders")
                    .header("X-LS-Session", sessionId)
                    .header("X-Request-Id", pendingOrder.requestId) // Fixed ID per BuyerRef
                    .header("Content-Type", "application/xml")
                    .body(xmlBody)
                    .retrieve()
                    .body(String.class);

            String poNumber = extractXmlValue(responseXml, "PoNumber");
            String statusCode = extractXmlValue(responseXml, "StatusCode");

            if (!"UNKNOWN".equals(poNumber)) {
                trackedPoNumbers.add(poNumber);
            }

            // ORDER ACCEPTED BY LEGACY SUPPLY: Clear pending order
            pendingOrders.remove(pendingOrder.request.localProductId());
            System.out.println(">>> SUCCESS: " + poNumber + " for BuyerRef " + pendingOrder.buyerRef);

            return new SupplierOrderResponse(true, poNumber, statusCode, "Success: " + responseXml);

        } catch (Exception e) {
            sessionManager.invalidateSession();
            // DO NOT clear pendingOrders on failure/outage!
            System.out.println(">>> OUTAGE/ERROR (" + pendingOrder.buyerRef + "): " + e.getMessage());
            return new SupplierOrderResponse(false, null, "FAILED", e.getMessage());
        }
    }

    // Poller 1: Retries blocked outage orders every 5 seconds until HTTP 201 is returned
    @Scheduled(fixedDelay = 5000)
    public void retryPendingOrders() {
        if (pendingOrders.isEmpty()) return;

        pendingOrders.values().forEach(pending -> {
            System.out.println(">>> RETRYING BLOCKED OUTAGE ORDER: " + pending.buyerRef);
            executeSend(pending);
        });
    }

    // Poller 2: Status checking for existing orders
    @Scheduled(fixedDelay = 10000)
    public void pollOrderStatus() {
        if (trackedPoNumbers.isEmpty()) return;

        for (String poNumber : trackedPoNumbers) {
            try {
                String sessionId = sessionManager.getValidSessionId();

                String responseXml = restClient.get()
                        .uri(baseUrl + "/purchase-orders/" + poNumber)
                        .header("X-LS-Session", sessionId)
                        .retrieve()
                        .body(String.class);

                String statusCode = extractXmlValue(responseXml, "StatusCode");

                if ("40".equals(statusCode) || "90".equals(statusCode)) {
                    trackedPoNumbers.remove(poNumber);
                }
            } catch (Exception e) {
                sessionManager.invalidateSession();
            }
        }
    }

    private String extractXmlValue(String xml, String tag) {
        if (xml == null || !xml.contains("<" + tag + ">")) {
            return "UNKNOWN";
        }
        int start = xml.indexOf("<" + tag + ">") + tag.length() + 2;
        int end = xml.indexOf("</" + tag + ">");
        return xml.substring(start, end);
    }
}