# Reflection Questions

# Lab 3: LegacySupply

Questions copied from my self-check page (https://legacysupply.onrender.com/verify). Times in the questions are local time on 2026-09-24.

## 1. Duplicate order

**Question:** LegacySupply holds more than one order for BuyerRef "REF-PROD-001-dafab921": PO-100042 (19:40:36) and PO-100043 (19:40:43). Reconstruct the sequence of events that produced the duplicate, and describe the change you made (or would make) so it cannot happen again.

**Answer:** At 19:40:36 my first adapter posted the order and LegacySupply created PO-100042, but the adapter did not get a usable answer, so it treated the order as failed. Seven seconds later its retry job posted the same BuyerRef again, and LegacySupply created PO-100043, which means the second request was not recognised as a repeat: it did not carry the same `X-Request-Id` as the first one (my record shows one order request sent with no `X-Request-Id` at all). The root cause was that BuyerRef and request ID only lived in memory, in a map inside `SupplierGatewayImpl`, and LegacySupply does not check BuyerRef (`GET /purchase-orders?buyerRef=REF-PROD-001-dafab921` returns `Count` 2). In the new adapter every reorder is first saved in `supplier_orders` with its own `buyer_ref` (`RO-<id>`) and `request_id`, and `SupplierOrderDispatcher` always sends that stored `request_id`, on every retry and after a restart. As a second check, if LegacySupply refuses a request the dispatcher looks the order up by BuyerRef and takes over the existing PO instead of sending a new one. RO-1, RO-2 and RO-3 (PO-102095, PO-102097, PO-102100) were placed this way with one PO each.

## 2. A 503 after the order was created

**Question:** At 19:19:09 your request for BuyerRef "AUTOREORDER-PROD-002" received a 503, but LegacySupply had already created PO-100022. Walk through exactly what your adapter did next, and explain why that did or did not result in a second order.

**Answer:** The adapter caught the 503, dropped its session and kept the order in its pending list, and the retry job sent it again a few seconds later with the same BuyerRef and the same `X-Request-Id`. Because the request ID was unchanged, LegacySupply did not process it a second time and answered with the order it already had, PO-100022, so no second order exists for that BuyerRef (my record lists only PO-100022, and counts this as a safe replay). What I learned is that a 503 from LegacySupply does not mean "nothing happened", so a failed call must never be answered by building a new request. The weak point at the time was that the request ID was only in memory, which is exactly what went wrong 21 minutes later in question 1. Now `LegacySupplyClient` retries a 503 at most three times with the same ID, and if all three fail the row stays `PENDING` in `supplier_orders` and is sent later with that same stored ID.

## 3. Undocumented status 90

**Question:** PO-100036 (BuyerRef "REF-PROD-001-c429b4ef") ended with StatusCode 90, which is not in the documentation. How did you work out what it means, and what does your system now do with the stock that will never arrive?

**Answer:** The manual only lists 10, 20, 30 and 40, and the status document for PO-100036 has the same fields as any other order with no reason text, so I had to work it out from behaviour. PO-100036 never reached 40 while the orders placed around it all did, it has stayed at 90 ever since (it still returned 90 on 2026-10-02), and the self-check page counts it under "Noticed a cancelled order", so I treat 90 as "cancelled by LegacySupply". In `LegacySupplyStatus` 90 maps to my own `CANCELLED` status: the row is final, tracking stops, and no units are ever added to stock for it. `SupplierOrderLifecycle` publishes a `SupplierOrderCancelledEvent`; the notification module records it, and Inventory checks the product and requests a new reorder straight away if stock is still below the threshold. Any other code I do not know becomes `NEEDS_REVIEW` instead: no stock moves, a notification is saved, and the order is only re-checked occasionally until a person decides (documented in INTEGRATION.md).

---

# Lab 4: Tiangge Marketplace

Questions copied from the Marketplace section of my self-check page on 2026-10-02. Times are local.

## 1. An event delivered twice

**Question:** Event evt_d6a1b91c017db899 (order TG-626M4F) reached your application twice, as seq 1 and seq 30, and you processed it once. Show the code and the stored data that made the second delivery harmless, and explain what would happen if your application restarted between the two.

**Answer:** When seq 1 arrived at 23:03:22, `ChannelOrderProcessor.decide` created shop order 33 through `OrderService.placeOrder` and, in the same database transaction, saved a `channel_orders` row linking `external_order_id` = TG-626M4F to `shop_order_id` = 33. When the order came again as seq 30, `ChannelOrderProcessor.orderPlaced` first ran `channelOrders.findByExternalOrderId("TG-626M4F")`, found that row and only logged "Tiangge order TG-626M4F delivered again (event evt_d6a1b91c017db899); already order 33" at 23:16:39. No second order was created and no stock was reserved again. I match on the Tiangge order ID rather than on the event ID or seq, because the seq is new on every delivery and one Tiangge order must be exactly one order of mine. The knowledge is a row in Postgres and not something in memory, so a restart between the two deliveries changes nothing: the new instance does the same lookup and finds the same row. The column also has a unique constraint, so even two copies of the app could not both insert it.

## 2. A backorder filled by a delivery

**Question:** Order TG-VJK6NL was backordered at 23:10:21 and accepted at 23:14:57, after PO-102207 was delivered at 23:14:47. Trace how the delivery reached your Inventory and what then resumed the backordered order.

**Answer:** At 23:10:20 PROD-001 was at 0 but reorder RO-4 (PO-102207, 24 units) had already been accepted by LegacySupply, so `ChannelOrderProcessor` created order 45 with `OrderService.placeBackorder` and answered BACKORDERED. The delivery was found by `SupplierOrderTracker`, which polls open purchase orders: `LegacySupplyClient.orderStatus` returned StatusCode 40, `SupplierOrderLifecycle` set RO-4 to DELIVERED (logged 23:14:50) and published `SupplierOrderDeliveredEvent`. `InventoryEventListener.handleSupplierDelivery` called `InventoryService.restock`, which added 24 units and published `StockChangedEvent`; that event made `ChannelStockSyncService` send PROD-001 = 24 to Tiangge at 23:14:53 and set a "check backorders" flag in `ChannelOrderProcessor`. On its next round, a few seconds later, `ChannelOrderPollerService` called `resolveBackorders`, which ran `OrderService.fillBackorder(45)`: the items were reserved all-or-nothing, the order became CONFIRMED, and the resolution ACCEPTED was saved and sent to Tiangge (the first attempt got a 503 and was retried). The stock after the reservation, PROD-001 = 22, was published at 23:15:00. The supplier module never calls Order or the channel directly; everything after the delivery is driven by the two events.

## 3. The restart test

**Question:** During your restart test your application was down for about 207 seconds while 5 orders arrived. How did the restarted application find those orders, and how did it avoid handling earlier ones again?

**Answer:** The position in the Tiangge feed is stored in the `channel_cursor` table and `ChannelOrderPollerService.readFeed` saves it after every batch, so it was still there after I stopped the app at 23:17. The new instance (28d4dc47, started 23:20:29) read that row and asked the feed only for events after it, which returned exactly what happened while the app was down: the cancellation of TG-9E44TH and the new orders TG-HATESX, TG-YRZ7QX, TG-ZM6QES, TG-T7JB8K and TG-HRGKZZ, all handled from 23:20:40 on. Earlier events were not returned at all because they are before the stored position. If Tiangge had delivered one of them again anyway, the `channel_orders` row for that order would have stopped a second order, as in question 1. Decisions that were saved but not yet confirmed as delivered when the app stopped are sent again at startup with the same content, which Tiangge treats as safe. Those five orders were decided late (65 to 180 seconds) only because the app was off, not because it had to search for them.
