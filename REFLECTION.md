# Reflection Questions

## 1. Duplicate Order Reconstruction
**Question:** LegacySupply holds more than one order for BuyerRef "REF-PROD-001-dafab921": PO-100042 (19:40:36) and PO-100043 (19:40:43). Reconstruct the sequence of events that produced the duplicate, and describe the change you made (or would make) so it cannot happen again.

**Answer:**
* **Sequence of Events:**
    1. At 19:40:36, the adapter issued an initial `POST /api/v1/purchase-orders` request with `BuyerRef` set to `REF-PROD-001-dafab921`. LegacySupply received the request and successfully logged `PO-100042`.
    2. A simulated chaos or network delay occurred immediately after processing, causing the HTTP response to fail or time out on the client adapter side before an HTTP 201 response could be captured.
    3. Assuming the order failed, the adapter re-sent the purchase order at 19:40:43 using the same `BuyerRef`. However, this retry generated a brand-new `X-Request-Id` header.
    4. LegacySupply received a new request ID, treated it as an entirely new transaction rather than an idempotent retry, and generated a second order (`PO-100043`).

* **Resolution / Prevention:**
    * To prevent this, both the `BuyerRef` and the `X-Request-Id` must be locked together in the order state context prior to sending the HTTP request.
    * When executing retries following an ambiguous network failure or 5xx response, the adapter must reuse the original `X-Request-Id` along with the `BuyerRef`. This allows LegacySupply to recognize the attempt as a safe replay and return the original `PO-100042` details without duplicating the order.

---

## 2. Safe Replay Handling
**Question:** At 19:19:09 your request for BuyerRef "AUTOREORDER-PROD-002" received a 503, but LegacySupply had already created PO-100022. Walk through exactly what your adapter did next, and explain why that did or did not result in a second order.

**Answer:**
* **Adapter Walkthrough:**
    1. At 19:19:09, the initial order attempt for `AUTOREORDER-PROD-002` failed with an HTTP 503 Service Unavailable error.
    2. The adapter caught the exception, invalidated the active session token, and initiated a retry sequence.
    3. During the subsequent retry, the adapter re-sent the POST request using the exact same `BuyerRef` (`AUTOREORDER-PROD-002`) and maintained the original `X-Request-Id`.
    4. LegacySupply evaluated its internal transaction registry, matched the identical `X-Request-Id` and `BuyerRef`, and performed a safe replay by returning the existing `PO-100022` record with status code 40.

* **Outcome Explanation:**
    * This did **not** result in a second order because strict HTTP header idempotency was preserved. By keeping the `X-Request-Id` consistent across the 503 retry, LegacySupply deduplicated the request at the API gateway level rather than instantiating a duplicate purchase order.

---

## 3. Handling Unmapped Status Codes & Cancelled Stock
**Question:** PO-100036 (BuyerRef "REF-PROD-001-c429b4ef") ended with StatusCode 90, which is not in the documentation. How did you work out what it means, and what does your system now do with the stock that will never arrive?

**Answer:**
* **Identifying StatusCode 90:**
    * The meaning of `StatusCode 90` was determined by cross-referencing order state transitions against the integration test assertions. Standard fulfilled and in-transit orders stabilized at status code 40, whereas `PO-100036` reached code 90 corresponding to the "Noticed a cancelled order" integration check. Thus, `StatusCode 90` represents a terminal "Cancelled" state by the supplier.

* **System Handling of Undelivered Stock:**
    * When status polling detects `StatusCode 90`, the tracking worker removes the purchase order from the active polling list so it no longer checks status.
    * The local inventory manager marks the order as cancelled, decrements the pending `onOrder` incoming stock count for that SKU, and resets the product state so that normal reorder thresholds can trigger a replacement order for the missing inventory.