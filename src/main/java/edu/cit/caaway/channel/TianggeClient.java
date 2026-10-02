package edu.cit.caaway.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Talks HTTP and JSON to Tiangge. Owns the headers, the timeout and the retries, and translates
 * Tiangge's documents into our own types so nothing else in the app sees them.
 */
@Component
class TianggeClient {

    private static final Logger log = LoggerFactory.getLogger(TianggeClient.class);

    private static final int MAX_ATTEMPTS = 3;
    // Wait before attempt 2 and before attempt 3.
    private static final long[] BACKOFF_MS = {300, 1000};

    private final RestClient rest;

    TianggeClient(ChannelContext context, @Value("${tiangge.base-url}") String baseUrl) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(Duration.ofSeconds(3));
        this.rest = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requests)
                .defaultHeader("X-Client-Id", context.getClientId())
                .defaultHeader("Authorization", "Bearer " + context.getApiKey())
                .defaultHeader("X-Client-Instance", context.getInstanceId())
                .build();
    }

    void heartbeat(String appName, String startedAt, long uptimeSeconds) {
        Map<String, Object> body = Map.of("appName", appName, "startedAt", startedAt, "uptimeSeconds", uptimeSeconds);
        send("heartbeat", () -> rest.post().uri("/instances/heartbeat")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity());
    }

    /** Publishes our listings. Each entry: product ID, title and the supplier item it is restocked from. */
    void publishListings(List<Listing> listings) {
        List<Map<String, String>> body = listings.stream()
                .map(l -> Map.of("sellerSku", l.productId(), "title", l.title(), "supplierSku", l.supplierItem()))
                .toList();
        send("listings", () -> rest.put().uri("/listings")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity());
    }

    void publishStock(Map<String, Integer> availableByProduct) {
        List<Map<String, Object>> body = new ArrayList<>();
        availableByProduct.forEach((productId, available) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sellerSku", productId);
            entry.put("available", Math.max(available, 0));
            body.add(entry);
        });
        send("stock", () -> rest.put().uri("/stock")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity());
    }

    FeedEvent.Page readFeed(long after, int limit) {
        Map<?, ?> body = send("feed", () -> rest.get()
                .uri(uri -> uri.path("/feed").queryParam("after", after).queryParam("limit", limit).build())
                .retrieve().body(Map.class));
        return toPage(body, after);
    }

    void sendDecision(String orderId, String decision, String shopOrderId, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", decision);
        body.put("shopOrderId", shopOrderId);
        if (reason != null && !reason.isBlank()) {
            body.put("reason", reason.length() > 200 ? reason.substring(0, 200) : reason);
        }
        send("decision " + orderId, () -> rest.post().uri("/orders/{id}/decision", orderId)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity());
    }

    void sendResolution(String orderId, String status) {
        send("resolution " + orderId, () -> rest.post().uri("/orders/{id}/resolution", orderId)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("status", status)).retrieve().toBodilessEntity());
    }

    void confirmCancellation(String orderId) {
        send("cancellation " + orderId, () -> rest.post().uri("/orders/{id}/cancellation", orderId)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("restocked", true)).retrieve().toBodilessEntity());
    }

    record Listing(String productId, String title, String supplierItem) {
    }

    /** One logical call: at most {@value MAX_ATTEMPTS} attempts when Tiangge is slow or answers 5xx. */
    private <T> T send(String what, Supplier<T> call) {
        String lastFailure = "no attempt made";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                pause(BACKOFF_MS[attempt - 2]);
            }
            try {
                return call.get();
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (status < 500) {
                    throw new TianggeRejectedException(status, errorCode(e), e.getResponseBodyAsString());
                }
                lastFailure = "HTTP " + status;
            } catch (ResourceAccessException e) {
                // Timeouts and connection failures. Tiangge may or may not have recorded the request.
                lastFailure = String.valueOf(e.getMessage());
            }
            log.warn("Tiangge {} attempt {}/{} failed: {}", what, attempt, MAX_ATTEMPTS, lastFailure);
        }
        throw new TianggeUnavailableException(what + " failed after " + MAX_ATTEMPTS + " attempts: " + lastFailure);
    }

    private static String errorCode(RestClientResponseException e) {
        try {
            Map<?, ?> body = e.getResponseBodyAs(Map.class);
            return body == null ? "unknown" : String.valueOf(body.get("error"));
        } catch (RuntimeException unreadable) {
            return "unknown";
        }
    }

    private static FeedEvent.Page toPage(Map<?, ?> body, long after) {
        List<FeedEvent> events = new ArrayList<>();
        long next = after;
        if (body != null) {
            if (body.get("events") instanceof List<?> rawEvents) {
                for (Object raw : rawEvents) {
                    if (raw instanceof Map<?, ?> event && event.get("seq") instanceof Number seq) {
                        events.add(new FeedEvent(seq.longValue(), text(event.get("eventId")), text(event.get("type")),
                                text(event.get("orderId")), toLines(event.get("lines"))));
                    }
                }
            }
            if (body.get("nextCursor") instanceof Number cursor) {
                next = cursor.longValue();
            }
        }
        return new FeedEvent.Page(events, next);
    }

    private static List<FeedEvent.Line> toLines(Object rawLines) {
        List<FeedEvent.Line> lines = new ArrayList<>();
        if (rawLines instanceof List<?> list) {
            for (Object raw : list) {
                if (raw instanceof Map<?, ?> line && line.get("qty") instanceof Number qty) {
                    lines.add(new FeedEvent.Line(text(line.get("sellerSku")), qty.intValue()));
                }
            }
        }
        return lines;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TianggeUnavailableException("Interrupted while waiting to retry");
        }
    }
}
