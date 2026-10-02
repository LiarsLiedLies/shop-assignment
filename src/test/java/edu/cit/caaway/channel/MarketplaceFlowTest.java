package edu.cit.caaway.channel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.cit.caaway.inventory.InventoryService;
import edu.cit.caaway.shop.OrderRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs the whole application against a fake Tiangge and a fake LegacySupply (one local server)
 * and an in-memory database, and walks through a shop's day: order, repeat delivery, backorder,
 * reject, supplier delivery, customer cancellation.
 */
@SpringBootTest
class MarketplaceFlowTest {

    /** Everything the app sent, in order: "POST /tiangge/v1/orders/TG-1/decision {...}". */
    private static final List<String> calls = new CopyOnWriteArrayList<>();
    private static final List<String> feed = new CopyOnWriteArrayList<>();
    private static final Map<String, Integer> supplierStatus = new ConcurrentHashMap<>();
    private static final AtomicInteger poNumbers = new AtomicInteger(500);
    private static final HttpServer server = startServer();

    @Autowired
    private InventoryService inventory;
    @Autowired
    private OrderRepository shopOrders;
    @Autowired
    private ChannelCursorRepository cursors;

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        registry.add("tiangge.base-url", () -> base + "/tiangge/v1");
        registry.add("legacysupply.base-url", () -> base + "/api/v1");
        registry.add("tiangge.poll-interval-ms", () -> "200");
        registry.add("supplier.dispatch-interval-ms", () -> "200");
        registry.add("supplier.tracking-interval-ms", () -> "300");
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @Test
    void runsTheShopUnattended() {
        // Go-live: heartbeat first, then listings with supplier items, then stock.
        await("go-live", () -> sent("PUT /tiangge/v1/stock"));
        assertTrue(calls.get(0).startsWith("POST /tiangge/v1/instances/heartbeat"), calls.get(0));
        String listings = find("PUT /tiangge/v1/listings");
        assertTrue(listings.contains("\"sellerSku\":\"PROD-001\"") && listings.contains("\"supplierSku\":\"LPB-9626\""), listings);
        assertEquals(10, inventory.getAvailable("PROD-001"));

        // 1. An order we can fill: accepted, and Tiangge gets the decision before the new stock.
        feed.add(placed(1, "evt-1", "TG-1", "PROD-001", 7));
        await("TG-1 decision", () -> sent("POST /tiangge/v1/orders/TG-1/decision"));
        assertTrue(find("POST /tiangge/v1/orders/TG-1/decision").contains("\"decision\":\"ACCEPTED\""));
        await("stock 3 published", () -> sent("PUT /tiangge/v1/stock [{\"sellerSku\":\"PROD-001\",\"available\":3}]"));
        assertTrue(indexOf("POST /tiangge/v1/orders/TG-1/decision")
                < indexOf("PUT /tiangge/v1/stock [{\"sellerSku\":\"PROD-001\",\"available\":3}]"));

        // Stock fell below the threshold, so the reorder rule placed a purchase order through the adapter.
        await("purchase order", () -> sent("POST /api/v1/purchase-orders"));
        assertTrue(find("POST /api/v1/purchase-orders").contains("<SupplierSku>LPB-9626</SupplierSku><Qty>1</Qty>"));
        await("purchase order tracked", () -> sent("GET /api/v1/purchase-orders/PO-501"));

        // 2. The same order delivered again under a new event: still one order, stock untouched.
        feed.add(placed(2, "evt-2", "TG-1", "PROD-001", 7));
        // 3. Short of stock, but a purchase order is on its way: backordered.
        feed.add(placed(3, "evt-3", "TG-2", "PROD-001", 5));
        // 4. Short of stock and nothing coming: rejected.
        feed.add(placed(4, "evt-4", "TG-3", "PROD-002", 11));
        await("TG-3 decision", () -> sent("POST /tiangge/v1/orders/TG-3/decision"));
        assertTrue(find("POST /tiangge/v1/orders/TG-2/decision").contains("\"decision\":\"BACKORDERED\""));
        assertTrue(find("POST /tiangge/v1/orders/TG-3/decision").contains("\"decision\":\"REJECTED\""));
        assertEquals(1, count("POST /tiangge/v1/orders/TG-1/decision"));
        assertEquals(3, inventory.getAvailable("PROD-001"));
        assertEquals(10, inventory.getAvailable("PROD-002"));
        assertEquals(3, shopOrders.count());

        // 5. The supplier delivers 24: stock 27, the backorder takes 5, Tiangge is told both.
        supplierStatus.put("PO-501", 40);
        await("backorder resolved", () -> sent("POST /tiangge/v1/orders/TG-2/resolution"));
        assertTrue(find("POST /tiangge/v1/orders/TG-2/resolution").contains("\"status\":\"ACCEPTED\""));
        await("stock 22 published", () -> sent("PUT /tiangge/v1/stock [{\"sellerSku\":\"PROD-001\",\"available\":22}]"));
        assertEquals(22, inventory.getAvailable("PROD-001"));

        // 6. The customer cancels the first order: restocked, confirmed, stock published; twice is harmless.
        feed.add(cancelled(5, "evt-5", "TG-1"));
        feed.add(cancelled(6, "evt-6", "TG-1"));
        await("cancellation confirmed", () -> sent("POST /tiangge/v1/orders/TG-1/cancellation"));
        await("stock 29 published", () -> sent("PUT /tiangge/v1/stock [{\"sellerSku\":\"PROD-001\",\"available\":29}]"));
        await("feed read to the end", () -> cursors.findById(ChannelCursor.FEED).map(c -> c.getPosition() == 6).orElse(false));
        assertEquals(29, inventory.getAvailable("PROD-001"));
        assertEquals(1, count("POST /tiangge/v1/orders/TG-1/cancellation"));
        assertEquals(3, shopOrders.count());
        // Only one purchase order was ever created for the one reorder.
        assertEquals(1, count("POST /api/v1/purchase-orders"));
    }

    // ---- the fake Tiangge + LegacySupply ----------------------------------------------------

    private static HttpServer startServer() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.setExecutor(Executors.newCachedThreadPool());
            http.createContext("/", MarketplaceFlowTest::answer);
            http.start();
            return http;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void answer(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String method = exchange.getRequestMethod();
        if (!path.endsWith("/feed")) {
            calls.add(method + " " + path + " " + body);
        }

        if (path.endsWith("/feed")) {
            long after = Long.parseLong(exchange.getRequestURI().getQuery().replaceAll(".*after=(\\d+).*", "$1"));
            List<String> events = feed.stream().filter(e -> seqOf(e) > after).toList();
            long next = events.isEmpty() ? after : seqOf(events.get(events.size() - 1));
            respond(exchange, 200, "{\"events\":[" + String.join(",", events) + "],\"nextCursor\":" + next + "}");
        } else if (path.startsWith("/tiangge/")) {
            respond(exchange, 200, "{}");
        } else if (path.endsWith("/auth/token")) {
            respond(exchange, 200, "<AuthResponse><SessionToken>t</SessionToken></AuthResponse>");
        } else if (method.equals("POST") && path.endsWith("/purchase-orders")) {
            String po = "PO-" + poNumbers.incrementAndGet();
            supplierStatus.put(po, 10);
            respond(exchange, 201, purchaseOrder("PurchaseOrderAck", po));
        } else if (path.contains("/purchase-orders/")) {
            respond(exchange, 200, purchaseOrder("PurchaseOrderStatus", path.substring(path.lastIndexOf('/') + 1)));
        } else {
            respond(exchange, 404, "");
        }
    }

    private static String purchaseOrder(String root, String po) {
        return "<" + root + "><PoNumber>" + po + "</PoNumber><StatusCode>" + supplierStatus.get(po)
                + "</StatusCode><Qty>1</Qty><Uom>CS</Uom></" + root + ">";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", body.startsWith("<") ? "application/xml" : "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String placed(long seq, String eventId, String orderId, String product, int qty) {
        return "{\"seq\":" + seq + ",\"eventId\":\"" + eventId + "\",\"type\":\"ORDER_PLACED\",\"orderId\":\"" + orderId
                + "\",\"lines\":[{\"sellerSku\":\"" + product + "\",\"qty\":" + qty + "}]}";
    }

    private static String cancelled(long seq, String eventId, String orderId) {
        return "{\"seq\":" + seq + ",\"eventId\":\"" + eventId + "\",\"type\":\"ORDER_CANCELLED\",\"orderId\":\"" + orderId + "\"}";
    }

    private static long seqOf(String event) {
        return Long.parseLong(event.replaceAll("^\\{\"seq\":(\\d+).*", "$1"));
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static boolean sent(String prefix) {
        return calls.stream().anyMatch(c -> c.startsWith(prefix));
    }

    private static long count(String prefix) {
        return calls.stream().filter(c -> c.startsWith(prefix)).count();
    }

    private static String find(String prefix) {
        return calls.stream().filter(c -> c.startsWith(prefix)).findFirst().orElseThrow();
    }

    private static int indexOf(String prefix) {
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("Timed out waiting for " + what + ". Calls so far:\n" + String.join("\n", calls));
    }
}
