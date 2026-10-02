package edu.cit.caaway.supplier;

import edu.cit.caaway.config.AppInstance;
import edu.cit.caaway.supplier.LegacySupplyXml.ErrorDoc;
import edu.cit.caaway.supplier.LegacySupplyXml.PurchaseOrderDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Talks HTTP and XML to LegacySupply. Owns the session, the timeout and the retries, so callers
 * only see a result, a {@link SupplierRejectedException} or a {@link SupplierUnavailableException}.
 */
@Component
class LegacySupplyClient {

    private static final Logger log = LoggerFactory.getLogger(LegacySupplyClient.class);

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(3);
    // Wait before attempt 2 and before attempt 3.
    private static final long[] BACKOFF_MS = {500, 1500};

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    private final String baseUrl;
    private final String clientId;
    private final String apiKey;
    private final Duration sessionMaxAge;
    private final String instanceId;

    private String sessionToken;
    private Instant sessionIssuedAt;

    LegacySupplyClient(@Value("${legacysupply.base-url}") String baseUrl,
                       @Value("${legacysupply.client-id}") String clientId,
                       @Value("${legacysupply.api-key}") String apiKey,
                       @Value("${legacysupply.session-max-age-seconds}") long sessionMaxAgeSeconds,
                       AppInstance appInstance) {
        this.baseUrl = baseUrl;
        this.clientId = clientId;
        this.apiKey = apiKey;
        this.sessionMaxAge = Duration.ofSeconds(sessionMaxAgeSeconds);
        this.instanceId = appInstance.getId();
    }

    /** Sends a purchase order. Safe to repeat with the same requestId: LegacySupply processes it once. */
    PurchaseOrderDoc placeOrder(String supplierSku, int cases, String buyerRef, String requestId) {
        String body = LegacySupplyXml.purchaseOrder(supplierSku, cases, buyerRef);
        return LegacySupplyXml.purchaseOrderDoc(call("POST", "/purchase-orders", body, requestId));
    }

    PurchaseOrderDoc orderStatus(String poNumber) {
        return LegacySupplyXml.purchaseOrderDoc(call("GET", "/purchase-orders/" + poNumber, null, null));
    }

    List<PurchaseOrderDoc> ordersByBuyerRef(String buyerRef) {
        String query = "?buyerRef=" + URLEncoder.encode(buyerRef, StandardCharsets.UTF_8);
        return LegacySupplyXml.purchaseOrderList(call("GET", "/purchase-orders" + query, null, null));
    }

    /** One logical call: at most {@value MAX_ATTEMPTS} attempts, each limited by the timeout. */
    private String call(String method, String path, String body, String requestId) {
        String lastFailure = "no attempt made";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                pause(BACKOFF_MS[attempt - 2]);
            }
            try {
                String token = currentSession();
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .timeout(CALL_TIMEOUT)
                        .header("X-LS-Session", token)
                        .header("X-Client-Instance", instanceId);
                if (requestId != null) {
                    request.header("X-Request-Id", requestId);
                }
                if (body == null) {
                    request.method(method, HttpRequest.BodyPublishers.noBody());
                } else {
                    request.header("Content-Type", "application/xml")
                            .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                }

                HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return response.body();
                }

                ErrorDoc error = LegacySupplyXml.error(response.body());
                lastFailure = "HTTP " + status + " " + error.code();
                if (status == 401) {
                    // The session stopped being accepted: forget it and sign in again on the next attempt.
                    forgetSession(token);
                } else if (status == 429) {
                    throw new SupplierUnavailableException("Request quota exceeded (" + error.code() + ")", true);
                } else if (status < 500) {
                    throw new SupplierRejectedException(error.code(), error.message());
                }
                log.warn("LegacySupply {} {} attempt {}/{} failed: {}", method, path, attempt, MAX_ATTEMPTS, lastFailure);
            } catch (IOException e) {
                // Includes timeouts. The request may or may not have been processed.
                lastFailure = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("LegacySupply {} {} attempt {}/{} failed: {}", method, path, attempt, MAX_ATTEMPTS, lastFailure);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SupplierUnavailableException("Interrupted while calling LegacySupply", false);
            }
        }
        throw new SupplierUnavailableException(
                method + " " + path + " failed after " + MAX_ATTEMPTS + " attempts: " + lastFailure, false);
    }

    /** Returns a session, signing in first when there is none or when ours is about to expire. */
    private synchronized String currentSession() throws IOException, InterruptedException {
        if (sessionToken != null && Instant.now().isBefore(sessionIssuedAt.plus(sessionMaxAge))) {
            return sessionToken;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/auth/token"))
                .timeout(CALL_TIMEOUT)
                .header("Content-Type", "application/xml")
                .header("X-Client-Instance", instanceId)
                .POST(HttpRequest.BodyPublishers.ofString(LegacySupplyXml.authRequest(clientId, apiKey)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            // Reported as an I/O failure so the caller counts it as a failed attempt and nothing is lost.
            throw new IOException("sign-in refused: HTTP " + response.statusCode() + " "
                    + LegacySupplyXml.error(response.body()).code());
        }
        try {
            sessionToken = LegacySupplyXml.sessionToken(response.body());
        } catch (IllegalArgumentException e) {
            throw new IOException("sign-in response unreadable", e);
        }
        sessionIssuedAt = Instant.now();
        log.info("Signed in to LegacySupply");
        return sessionToken;
    }

    private synchronized void forgetSession(String rejectedToken) {
        // Only drop the token that was rejected; another thread may already have signed in again.
        if (rejectedToken.equals(sessionToken)) {
            sessionToken = null;
        }
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SupplierUnavailableException("Interrupted while waiting to retry", false);
        }
    }
}
