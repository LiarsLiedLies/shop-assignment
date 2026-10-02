package edu.cit.caaway.supplier;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.cit.caaway.config.AppInstance;
import edu.cit.caaway.supplier.LegacySupplyXml.PurchaseOrderDoc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the client against a local stand-in for LegacySupply that misbehaves on purpose. */
class LegacySupplyClientTest {

    private static final String ACK = "<PurchaseOrderAck><PoNumber>PO-1</PoNumber><StatusCode>10</StatusCode>"
            + "<SupplierSku>LPB-1517</SupplierSku><Qty>2</Qty><Uom>CS</Uom><BuyerRef>RO-1</BuyerRef></PurchaseOrderAck>";

    /** What the fake server does with the n-th order request (1-based). */
    private interface OrderBehaviour {
        void handle(int attempt, HttpExchange exchange) throws Exception;
    }

    private HttpServer server;
    private LegacySupplyClient client;
    private OrderBehaviour behaviour;
    private final AtomicInteger signIns = new AtomicInteger();
    private final AtomicInteger orderRequests = new AtomicInteger();
    private final List<String> requestIds = new CopyOnWriteArrayList<>();
    private final List<String> sessions = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/auth/token", exchange -> respond(exchange, 200,
                "<AuthResponse><SessionToken>token-" + signIns.incrementAndGet() + "</SessionToken></AuthResponse>"));
        server.createContext("/purchase-orders", exchange -> {
            requestIds.add(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            sessions.add(exchange.getRequestHeaders().getFirst("X-LS-Session"));
            try {
                behaviour.handle(orderRequests.incrementAndGet(), exchange);
            } catch (Exception e) {
                exchange.close();
            }
        });
        server.start();
        client = new LegacySupplyClient("http://127.0.0.1:" + server.getAddress().getPort(),
                "test-client", "test-key", 210, new AppInstance());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void signsInOnceAndPlacesTheOrder() {
        behaviour = (attempt, exchange) -> respond(exchange, 201, ACK);

        PurchaseOrderDoc ack = client.placeOrder("LPB-1517", 2, "RO-1", "req-1");
        client.placeOrder("LPB-1517", 2, "RO-1", "req-1");

        assertEquals("PO-1", ack.poNumber());
        assertEquals(10, ack.statusCode());
        assertEquals(1, signIns.get());
    }

    @Test
    void signsInAgainWhenTheSessionIsNoLongerAccepted() {
        behaviour = (attempt, exchange) -> {
            if (attempt == 1) {
                respond(exchange, 401, "<LSError><Code>E-AUTH-07</Code><Message>Session not valid.</Message></LSError>");
            } else {
                respond(exchange, 201, ACK);
            }
        };

        assertEquals("PO-1", client.placeOrder("LPB-1517", 2, "RO-1", "req-1").poNumber());
        assertEquals(2, signIns.get());
        assertEquals(List.of("token-1", "token-2"), sessions);
    }

    @Test
    void retriesAfter503WithTheSameRequestId() {
        behaviour = (attempt, exchange) -> {
            if (attempt < 3) {
                respond(exchange, 503, "<LSError><Code>E-SYS-50</Code><Message>Processing error.</Message></LSError>");
            } else {
                respond(exchange, 201, ACK);
            }
        };

        assertEquals("PO-1", client.placeOrder("LPB-1517", 2, "RO-1", "req-1").poNumber());
        assertEquals(List.of("req-1", "req-1", "req-1"), requestIds);
    }

    @Test
    void givesUpAfterThreeAttemptsDuringAnOutage() {
        behaviour = (attempt, exchange) ->
                respond(exchange, 503, "<LSError><Code>E-SYS-99</Code><Message>Service unavailable.</Message></LSError>");

        SupplierUnavailableException e = assertThrows(SupplierUnavailableException.class,
                () -> client.placeOrder("LPB-1517", 2, "RO-1", "req-1"));

        assertFalse(e.isRateLimited());
        assertEquals(3, orderRequests.get());
    }

    @Test
    void timesOutSlowAnswersAndStopsAfterThreeAttempts() {
        behaviour = (attempt, exchange) -> {
            Thread.sleep(4000);
            respond(exchange, 201, ACK);
        };

        long start = System.currentTimeMillis();
        assertThrows(SupplierUnavailableException.class, () -> client.placeOrder("LPB-1517", 2, "RO-1", "req-1"));
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(3, orderRequests.get());
        assertEquals(List.of("req-1", "req-1", "req-1"), requestIds);
        // 3 attempts of 3 s plus 2 s of backoff; a client without a timeout would take 12 s or more.
        assertTrue(elapsed < 11_800, "took " + elapsed + " ms");
    }

    @Test
    void doesNotRetryARefusedOrder() {
        behaviour = (attempt, exchange) ->
                respond(exchange, 422, "<LSError><Code>E-QTY-11</Code><Message>Quantity invalid.</Message></LSError>");

        SupplierRejectedException e = assertThrows(SupplierRejectedException.class,
                () -> client.placeOrder("LPB-1517", 100, "RO-1", "req-1"));

        assertEquals("E-QTY-11", e.getCode());
        assertEquals(1, orderRequests.get());
    }

    @Test
    void doesNotRetryWhenTheQuotaIsExceeded() {
        behaviour = (attempt, exchange) ->
                respond(exchange, 429, "<LSError><Code>E-RATE-03</Code><Message>Request quota exceeded.</Message></LSError>");

        SupplierUnavailableException e = assertThrows(SupplierUnavailableException.class,
                () -> client.orderStatus("PO-1"));

        assertTrue(e.isRateLimited());
        assertEquals(1, orderRequests.get());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/xml");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
