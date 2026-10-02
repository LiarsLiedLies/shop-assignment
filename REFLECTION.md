# Reflection Questions

# Lab 3: LegacySupply

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

## 1.

**Question:** Event evt_d6a1b91c017db899 (order TG-626M4F) reached your application twice, as seq 1 and seq 30, and you processed it once. Show the code and the stored data that made the second delivery harmless, and explain what would happen if your application restarted between the two.

**Answer:** The first time (seq 1, 23:03:22) `ChannelOrderProcessor.decide()` made order 33 with `orderService.placeOrder()` and saved a row in the `channel_orders` table: `external_order_id = TG-626M4F`, `shop_order_id = 33`. Before making any order, `orderPlaced()` runs `channelOrders.findByExternalOrderId(event.orderId())`. On seq 30 that lookup found the row, so it skipped the order and only printed "Tiangge order TG-626M4F delivered again ... already order 33" at 23:16:39. I check the order ID and not the seq because the seq is different every time. If the app restarted in between, nothing changes, because the row is in the database and not in memory, and `external_order_id` is also unique so a second insert would fail anyway.

## 2.

**Question:** Order TG-VJK6NL was backordered at 23:10:21 and accepted at 23:14:57, after PO-102207 was delivered at 23:14:47. Trace how the delivery reached your Inventory and what then resumed the backordered order.

**Answer:** PROD-001 was at 0 but PO-102207 was already placed, so the order was saved as BACKORDERED (order 45) without reserving anything. `SupplierOrderTracker` checks open purchase orders every 15 seconds, and when LegacySupply returned status 40, `SupplierOrderLifecycle` marked the reorder DELIVERED and published `SupplierOrderDeliveredEvent`. `InventoryEventListener` caught that event and called `inventoryService.restock()`, which added 24 units and published `StockChangedEvent`. That event does two things in the channel module: `ChannelStockSyncService` sends the new stock (24) to Tiangge, and `ChannelOrderProcessor` sets a flag to recheck backorders. On the next poll `resolveBackorders()` called `orderService.fillBackorder(45)`, the stock was reserved, the order became CONFIRMED, and the app sent the resolution ACCEPTED and then the new stock (22).

## 3.

**Question:** During your restart test your application was down for about 207 seconds while 5 orders arrived. How did the restarted application find those orders, and how did it avoid handling earlier ones again?

**Answer:** The app saves how far it has read the feed in the `channel_cursor` table, after every batch, in `ChannelOrderPollerService.readFeed()`. When I started it again at 23:20, it read that number from the database and asked Tiangge for events after it, so it only got what came in while it was off: the 5 orders and one cancellation. Older orders are before the cursor so Tiangge did not send them again. Even if one did come again, the `channel_orders` check from question 1 would stop a second order. Those 5 orders were answered late only because the app was off for 3 minutes.
