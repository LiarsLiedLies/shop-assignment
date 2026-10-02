package edu.cit.caaway.channel;

import edu.cit.caaway.channel.ChannelOrder.State;
import edu.cit.caaway.inventory.InventoryService;
import edu.cit.caaway.inventory.StockChangedEvent;
import edu.cit.caaway.shop.Order;
import edu.cit.caaway.shop.OrderItemRequest;
import edu.cit.caaway.shop.OrderService;
import edu.cit.caaway.supplier.SupplierGateway;
import edu.cit.caaway.supplier.SupplierOrderCancelledEvent;
import edu.cit.caaway.supplier.SupplierOrderNeedsAttentionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turns Tiangge feed events into work for our own Order module and reports the outcome back.
 * Every step is saved before Tiangge is told, so a failed call or a restart only means the same
 * answer is sent again later, never a second order.
 */
@Component
class ChannelOrderProcessor {

    private static final Logger log = LoggerFactory.getLogger(ChannelOrderProcessor.class);

    private static final String ACCEPTED = "ACCEPTED";
    private static final String REJECTED = "REJECTED";
    private static final String BACKORDERED = "BACKORDERED";
    private static final String CANCELLED = "CANCELLED";
    private static final List<State> UNSENT =
            List.of(State.DECISION_PENDING, State.RESOLUTION_PENDING, State.CANCELLATION_PENDING);

    private final ChannelOrderRepository channelOrders;
    private final OrderService orderService;
    private final InventoryService inventoryService;
    private final SupplierGateway supplierGateway;
    private final TianggeClient client;
    private final ChannelStockSyncService stockSync;
    private final TransactionTemplate transaction;

    // Both start true so a restarted app first finishes what the previous run left open.
    private final AtomicBoolean unsentWork = new AtomicBoolean(true);
    private final AtomicBoolean backordersToCheck = new AtomicBoolean(true);
    // Orders whose decision Tiangge has received but whose row still says DECISION_PENDING.
    private final Set<Long> deliveredDecisions = ConcurrentHashMap.newKeySet();
    private final Set<Long> deliveredBackorders = ConcurrentHashMap.newKeySet();

    ChannelOrderProcessor(ChannelOrderRepository channelOrders, OrderService orderService,
                          InventoryService inventoryService, SupplierGateway supplierGateway, TianggeClient client,
                          ChannelStockSyncService stockSync, PlatformTransactionManager transactionManager) {
        this.channelOrders = channelOrders;
        this.orderService = orderService;
        this.inventoryService = inventoryService;
        this.supplierGateway = supplierGateway;
        this.client = client;
        this.stockSync = stockSync;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    void handle(FeedEvent event) {
        if ("ORDER_PLACED".equals(event.type())) {
            stockSync.beforeStockIsPublished(() -> orderPlaced(event));
        } else if ("ORDER_CANCELLED".equals(event.type())) {
            stockSync.beforeStockIsPublished(() -> orderCancelled(event));
        } else {
            log.warn("Ignoring feed event {} of unknown type {}", event.eventId(), event.type());
        }
    }

    // ---- new orders -------------------------------------------------------------------------

    private Void orderPlaced(FeedEvent event) {
        ChannelOrder known = channelOrders.findByExternalOrderId(event.orderId()).orElse(null);
        if (known != null) {
            log.info("Tiangge order {} delivered again (event {}); already order {}",
                    event.orderId(), event.eventId(), known.getShopOrderId());
        } else {
            // One transaction: the shop order, its stock reservation and the link to the Tiangge order.
            known = transaction.execute(status -> decide(event));
        }
        if (known.getState() == State.DECISION_PENDING) {
            sendDecision(known);
        }
        return null;
    }

    private ChannelOrder decide(FeedEvent event) {
        Map<String, Integer> wanted = mergeLines(event.lines());
        List<OrderItemRequest> items = toItems(wanted);

        boolean inStock = wanted.entrySet().stream()
                .allMatch(line -> inventoryService.validateStock(line.getKey(), line.getValue()));
        Order order;
        String decision;
        if (!inStock && restockWillCover(wanted)) {
            order = orderService.placeBackorder(items);
            decision = BACKORDERED;
        } else {
            // Same all-or-nothing logic as an order from our own UI.
            order = orderService.placeOrder(items);
            decision = "CONFIRMED".equals(order.getStatus()) ? ACCEPTED : REJECTED;
        }
        log.info("Tiangge order {} {} -> order {} {}", event.orderId(), wanted, order.getId(), decision);
        return channelOrders.save(
                new ChannelOrder(event.orderId(), order.getId(), decision, order.getReason(), encode(wanted)));
    }

    /**
     * A short order may wait only when the supplier has already accepted a purchase order for every
     * product we are short of, and what is coming is not already promised to earlier backorders.
     */
    private boolean restockWillCover(Map<String, Integer> wanted) {
        Map<String, Integer> promised = new LinkedHashMap<>();
        for (ChannelOrder other : channelOrders.findByStateInOrderByIdAsc(List.of(State.WAITING_FOR_STOCK, State.DECISION_PENDING))) {
            if (BACKORDERED.equals(other.getDecision())) {
                decode(other.getLines()).forEach((product, quantity) -> promised.merge(product, quantity, Integer::sum));
            }
        }
        for (Map.Entry<String, Integer> line : wanted.entrySet()) {
            int stock = inventoryService.getAvailable(line.getKey());
            int coming = supplierGateway.unitsInTransit(line.getKey());
            if (stock < line.getValue() && coming == 0) {
                return false;
            }
            if (stock + coming - promised.getOrDefault(line.getKey(), 0) < line.getValue()) {
                return false;
            }
        }
        return true;
    }

    private void sendDecision(ChannelOrder order) {
        try {
            client.sendDecision(order.getExternalOrderId(), order.getDecision(), shopOrderId(order), order.getReason());
        } catch (TianggeUnavailableException e) {
            log.warn("Decision for {} not delivered yet: {}", order.getExternalOrderId(), e.getMessage());
            unsentWork.set(true);
            return;
        } catch (TianggeRejectedException e) {
            // Tiangge will never take this decision (for example it already has one); stop repeating it.
            log.error("Tiangge refused the decision for {}: {}", order.getExternalOrderId(), e.getMessage());
        }
        // Tiangge has the decision. The row is moved on later, together with the rest of the batch.
        (BACKORDERED.equals(order.getDecision()) ? deliveredBackorders : deliveredDecisions).add(order.getId());
    }

    /**
     * Records in one statement per kind that Tiangge received the decisions of this batch. If the
     * app stops before this runs, the same decisions are simply sent again after the restart.
     */
    void saveDeliveredDecisions() {
        if (deliveredDecisions.isEmpty() && deliveredBackorders.isEmpty()) {
            return;
        }
        List<Long> done = List.copyOf(deliveredDecisions);
        List<Long> waiting = List.copyOf(deliveredBackorders);
        try {
            transaction.executeWithoutResult(status -> {
                if (!done.isEmpty()) {
                    channelOrders.advance(done, State.DECISION_PENDING, State.DONE);
                }
                if (!waiting.isEmpty()) {
                    channelOrders.advance(waiting, State.DECISION_PENDING, State.WAITING_FOR_STOCK);
                }
            });
            deliveredDecisions.removeAll(done);
            deliveredBackorders.removeAll(waiting);
            if (!waiting.isEmpty()) {
                backordersToCheck.set(true);
            }
        } catch (RuntimeException e) {
            log.warn("Could not record delivered decisions yet: {}", e.getMessage());
        }
    }

    // ---- customer cancellations -------------------------------------------------------------

    private Void orderCancelled(FeedEvent event) {
        ChannelOrder order = channelOrders.findByExternalOrderId(event.orderId()).orElse(null);
        if (order == null) {
            log.warn("Cancellation for Tiangge order {} that we never received; nothing to restock", event.orderId());
            return null;
        }
        if (order.isCancelled() && order.getState() == State.DONE) {
            log.info("Cancellation of {} delivered again (event {}); already confirmed", event.orderId(), event.eventId());
            return null;
        }
        if (order.getState() == State.DECISION_PENDING) {
            sendDecision(order);
            saveDeliveredDecisions();
        }
        if (!order.isCancelled()) {
            order = transaction.execute(status -> cancelShopOrder(event.orderId()));
        }
        sendCancellation(order);
        return null;
    }

    private ChannelOrder cancelShopOrder(String externalOrderId) {
        ChannelOrder order = channelOrders.findByExternalOrderId(externalOrderId).orElseThrow();
        if (!REJECTED.equals(order.getDecision()) && !CANCELLED.equals(order.getResolution())) {
            // The Lab 2 cancellation: puts the items back and Inventory announces the new stock.
            orderService.cancelOrder(order.getShopOrderId());
        }
        order.setCancelled(true);
        order.setState(State.CANCELLATION_PENDING);
        log.info("Tiangge order {} cancelled by customer; order {} cancelled", externalOrderId, order.getShopOrderId());
        return channelOrders.save(order);
    }

    private void sendCancellation(ChannelOrder order) {
        try {
            client.confirmCancellation(order.getExternalOrderId());
        } catch (TianggeUnavailableException e) {
            log.warn("Cancellation of {} not confirmed yet: {}", order.getExternalOrderId(), e.getMessage());
            unsentWork.set(true);
            return;
        } catch (TianggeRejectedException e) {
            log.error("Tiangge refused the cancellation of {}: {}", order.getExternalOrderId(), e.getMessage());
        }
        order.setState(State.DONE);
        channelOrders.save(order);
        // Tiangge expects a stock figure after every confirmed cancellation.
        stockSync.markChanged(decode(order.getLines()).keySet());
    }

    // ---- backorders -------------------------------------------------------------------------

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onStockChanged(StockChangedEvent event) {
        backordersToCheck.set(true);
    }

    @EventListener
    void onSupplierCancelled(SupplierOrderCancelledEvent event) {
        backordersToCheck.set(true);
    }

    @EventListener
    void onSupplierProblem(SupplierOrderNeedsAttentionEvent event) {
        backordersToCheck.set(true);
    }

    /** Fills waiting backorders, oldest first, when stock or the supplier situation changed. */
    void resolveBackorders() {
        if (!backordersToCheck.getAndSet(false)) {
            return;
        }
        try {
            for (ChannelOrder waiting : channelOrders.findByStateOrderByIdAsc(State.WAITING_FOR_STOCK)) {
                stockSync.beforeStockIsPublished(() -> {
                    ChannelOrder order = transaction.execute(status -> tryToFill(waiting.getId()));
                    if (order.getState() == State.RESOLUTION_PENDING) {
                        sendResolution(order);
                    }
                    return null;
                });
            }
        } catch (RuntimeException e) {
            backordersToCheck.set(true);
            throw e;
        }
    }

    private ChannelOrder tryToFill(Long channelOrderId) {
        ChannelOrder order = channelOrders.findById(channelOrderId).orElseThrow();
        if (order.getState() != State.WAITING_FOR_STOCK) {
            return order;
        }
        Order shopOrder = orderService.fillBackorder(order.getShopOrderId());
        if ("CONFIRMED".equals(shopOrder.getStatus())) {
            order.setResolution(ACCEPTED);
        } else if (!restockStillComing(decode(order.getLines()))) {
            orderService.cancelOrder(order.getShopOrderId());
            order.setResolution(CANCELLED);
        } else {
            return order;
        }
        order.setState(State.RESOLUTION_PENDING);
        log.info("Backorder {} (order {}) resolved as {}", order.getExternalOrderId(), order.getShopOrderId(),
                order.getResolution());
        return channelOrders.save(order);
    }

    private boolean restockStillComing(Map<String, Integer> wanted) {
        for (Map.Entry<String, Integer> line : wanted.entrySet()) {
            boolean shortOf = inventoryService.getAvailable(line.getKey()) < line.getValue();
            if (shortOf && !supplierGateway.hasOpenReorder(line.getKey())) {
                return false;
            }
        }
        return true;
    }

    private void sendResolution(ChannelOrder order) {
        try {
            client.sendResolution(order.getExternalOrderId(), order.getResolution());
        } catch (TianggeUnavailableException e) {
            log.warn("Resolution of {} not delivered yet: {}", order.getExternalOrderId(), e.getMessage());
            unsentWork.set(true);
            return;
        } catch (TianggeRejectedException e) {
            log.error("Tiangge refused the resolution of {}: {}", order.getExternalOrderId(), e.getMessage());
        }
        order.setState(State.DONE);
        channelOrders.save(order);
    }

    // ---- answers Tiangge has not received yet -----------------------------------------------

    /** Repeats answers that could not be delivered earlier. Same content, so repeating is safe. */
    void resendUnsent() {
        if (!unsentWork.getAndSet(false)) {
            return;
        }
        for (ChannelOrder order : channelOrders.findByStateInOrderByIdAsc(UNSENT)) {
            stockSync.beforeStockIsPublished(() -> {
                switch (order.getState()) {
                    case DECISION_PENDING -> sendDecision(order);
                    case RESOLUTION_PENDING -> sendResolution(order);
                    case CANCELLATION_PENDING -> sendCancellation(order);
                    default -> { }
                }
                return null;
            });
        }
    }

    // ---- translation ------------------------------------------------------------------------

    private static String shopOrderId(ChannelOrder order) {
        return "SO-" + order.getShopOrderId();
    }

    /** Two lines for the same product count as one, so the stock check sees the full quantity. */
    private static Map<String, Integer> mergeLines(List<FeedEvent.Line> lines) {
        Map<String, Integer> wanted = new LinkedHashMap<>();
        for (FeedEvent.Line line : lines) {
            wanted.merge(line.productId(), line.quantity(), Integer::sum);
        }
        return wanted;
    }

    private static List<OrderItemRequest> toItems(Map<String, Integer> wanted) {
        List<OrderItemRequest> items = new ArrayList<>();
        wanted.forEach((product, quantity) -> items.add(new OrderItemRequest(product, quantity)));
        return items;
    }

    private static String encode(Map<String, Integer> wanted) {
        StringBuilder text = new StringBuilder();
        wanted.forEach((product, quantity) -> text.append(text.isEmpty() ? "" : ",").append(product).append(':').append(quantity));
        return text.toString();
    }

    private static Map<String, Integer> decode(String lines) {
        Map<String, Integer> wanted = new LinkedHashMap<>();
        if (lines != null && !lines.isBlank()) {
            for (String line : lines.split(",")) {
                int split = line.lastIndexOf(':');
                wanted.put(line.substring(0, split), Integer.parseInt(line.substring(split + 1)));
            }
        }
        return wanted;
    }
}
