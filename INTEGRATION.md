# LegacySupply integration notes & observations

## 1. Product mapping

| My product ID | My product name     | LegacySupply SupplierSku | LegacySupply Description | PackSize |
|---------------|---------------------|--------------------------|--------------------------|----------|
| PROD-001      | USB-C Cable 1M      | LPB-9626                 | USB-C CABLE 1M BRAIDED   | 24       |
| PROD-002      | Wireless Mouse      | LPB-1517                 | WIRELESS MOUSE 2.4GHZ    | 6        |
| PROD-003      | Mechanical Keyboard | LPB-1455                 | KEYBOARD MECH TKL        | 24       |

The mapping lives only in `supplier/SupplierCatalog.java`. Inventory and Order know product IDs and single units.

## 2. Sessions

How it works:

1. `POST /auth/token` with an `AuthRequest` document (ClientId, ApiKey) returns `AuthResponse` with a `SessionToken` and `IssuedAt`.
2. Every other call sends the token in the `X-LS-Session` header. `GET /ping` is the only call that needs no session.
3. There is no refresh or sign-out call. When the token stops working you sign in again and get a new one.

How long it lasts (measured, the manual only says "short-lived"):

| Test | What I did | Result |
|------|------------|--------|
| Active session | Signed in at 14:34:21, then `GET /catalog` every ~10 s with the same token | 200 up to +233 s, `401 E-AUTH-07 Session not valid` at +244 s |
| Idle session | Signed in at 14:38:32, sent nothing for 224 s, then one `GET /catalog`; one more 30 s later | 200 after 224 s of silence, `401 E-AUTH-07` at +254 s |

So a session lasts about **240 seconds (4 minutes) counted from sign-in**. Using the session does not extend it.

Two different 401s matter: an expired token gives `E-AUTH-07`, a token LegacySupply never issued gives `E-AUTH-03`.

What the adapter does (`LegacySupplyClient`): it keeps one token in memory and signs in again before using a token older than 210 s (`legacysupply.session-max-age-seconds`). If a call still gets a 401, it forgets the token, signs in and repeats the call. Nobody pastes a token anywhere.

## 3. Error codes I received

| Code | HTTP | What I sent that caused it |
|------|------|----------------------------|
| E-AUTH-01 | 401 | `POST /auth/token` with a wrong ApiKey |
| E-AUTH-02 | 401 | `GET /catalog` with no `X-LS-Session` header |
| E-AUTH-03 | 401 | A token LegacySupply never issued. I first hit this by accident: my script cut the token out of the response wrongly, so every call after sign-in failed |
| E-AUTH-07 | 401 | A real token used 244 s after sign-in (expired) |
| E-FMT-01 | 415 | `POST /purchase-orders` with `Content-Type: application/json` |
| E-FMT-02 | 400 | XML body with unclosed tags |
| E-REF-05 | 400 | Order with no `BuyerRef`; also a `BuyerRef` of 41 characters (limit is 40) |
| E-SKU-02 | 422 | Order using my own product ID (`PROD-001`) as `SupplierSku` |
| E-QTY-11 | 422 | `Qty` of 0, of 100 (allowed range is 1 to 99) and of 1.5 (must be whole) |
| E-PO-04 | 404 | `GET /purchase-orders/PO-999999` |
| E-QRY-06 | 400 | `GET /purchase-orders` without `?buyerRef=` |
| E-SYS-50 / 503 | 503 | Received on 2026-09-24 at 19:19:09 (local) on an order POST. LegacySupply had created PO-100022 anyway, so a 503 does not mean "nothing happened" |

Listed in the manual but not received by me: `E-IDEM-04` (same `X-Request-Id` with different content), `E-RATE-03` (quota) and `E-SYS-99` (outage).

Other things the probes showed:

- `GET /purchase-orders?buyerRef=` with a reference that has no orders is not an error: it returns `200` with `<Count>0</Count>`.
- LegacySupply does not check that `BuyerRef` is unique. `REF-PROD-001-dafab921` returns `<Count>2</Count>` (PO-100042 and PO-100043), a duplicate my first adapter created on 2026-09-24.

How the adapter treats them:

| Kind | Codes | Reaction |
|------|-------|----------|
| Session | 401 | Sign in again, repeat the call |
| Temporary | 503, timeout, connection failure | Retry with backoff (0.5 s, then 1.5 s), 3 attempts at most; if still failing the reorder stays `PENDING` |
| Quota | 429 | No retry; the scheduled job pauses 60 to 90 s |
| Refused | other 4xx | No retry. The adapter looks the order up by `BuyerRef`; if LegacySupply already has it, the PO is taken over, otherwise the reorder becomes `REJECTED` and a notification is saved |

## 4. Qty and Uom

`Qty` is not a number of items. It is a number of **cases**, which is what `Uom` = `CS` in the acknowledgement says. One case holds `PackSize` items of that SupplierSku, so the items that arrive are `Qty x PackSize`. LegacySupply only sells whole cases, 1 to 99 per order.

My shop thinks in single units, so the adapter converts and rounds up:

    cases = ceil(units needed / PackSize)        units arriving = cases x PackSize

Worked example: the reorder rule asks for 10 Wireless Mice (PROD-002, LPB-1517, PackSize 6). 10 / 6 = 1.67, rounded up to 2. The adapter sends `<Qty>2</Qty>`, LegacySupply answers `<Qty>2</Qty><Uom>CS</Uom>`, and when the order is delivered Inventory adds 2 x 6 = **12** units, not 2 and not 10. That is reorder `RO-2` / `PO-102097` in my record.

Rounding down would leave the shop short (1 case = 6 mice when 10 are needed), so the adapter always rounds up and stores both numbers (`cases`, `units`) in `supplier_orders`.

## 5. Order status and what my system does

| LegacySupply StatusCode | Meaning | My `ReorderStatus` | What happens |
|-------------------------|---------|--------------------|--------------|
| (not sent yet) | | `PENDING` | Scheduled job keeps trying to send it |
| 10 | Accepted (manual) | `PLACED` | Tracked |
| 20 | Picking (manual) | `PICKING` | Tracked |
| 30 | Shipped (manual) | `SHIPPED` | Tracked |
| 40 | Delivered (manual) | `DELIVERED` | `SupplierOrderDeliveredEvent`; Inventory adds `units`; tracking stops |
| 90 | Not in the manual. Cancelled by LegacySupply | `CANCELLED` | `SupplierOrderCancelledEvent`; no stock is added; Inventory reorders if the product is still low; tracking stops |
| anything else | Unknown | `NEEDS_REVIEW` | See below |
| (order refused) | | `REJECTED` | Notification saved; never resent |

### Status 90

PO-100036 ended with StatusCode 90, which the manual does not list. The status document for it looks like any other (same fields, no reason text), so the code itself is the only signal. I read it as "cancelled" because the order never reached 40 while every order placed around it did, and because the self-check page counts it under "Noticed a cancelled order".

Decision: 90 is final. The units are never added to stock. Because that reorder is no longer open, the product is free to be reordered: Inventory listens for the cancellation and asks for a new reorder right away if stock is still under the threshold.

### Any other status I did not expect

Decision: **never guess**. The reorder becomes `NEEDS_REVIEW` and:

- no stock is added, because I do not know whether goods are coming;
- a notification is saved once ("REORDER NEEDS REVIEW ...") so a person can look at it;
- the order is still checked, but only every 8th tracking round (about every 2 minutes), so if LegacySupply later reports 40 or 90 it is handled normally without wasting quota;
- it does not block a new reorder for the same product.

A missing or non-numeric StatusCode is treated the same way.

## 6. Never twice, never lost

- Every reorder is first saved in `supplier_orders` as `PENDING` with `buyer_ref` = `RO-<id>` and a random `request_id`. This happens in the same database transaction as the stock change, with no network call.
- `SupplierOrderDispatcher` (`@Scheduled`, every 5 s) sends `PENDING` rows. The `X-Request-Id` header is always the stored `request_id`, so it is the same on every retry and after a restart, and LegacySupply processes it once.
- If a call times out (3 s) or returns 503 three times, the row simply stays `PENDING`. After a failed round the dispatcher waits 5, 10, 20, 40, then 60 s before the next round.
- The row only leaves `PENDING` after LegacySupply's answer is saved. If the application dies between the answer and the save, the same request is sent again and LegacySupply returns the same PO.

## 7. Request quota

- Only open orders (`PLACED`, `PICKING`, `SHIPPED`) are polled, every 15 s. Final orders are never polled again.
- At most one open reorder per product, so with three products that is at most 12 status calls a minute.
- On `E-RATE-03` tracking pauses for 90 s instead of retrying.
