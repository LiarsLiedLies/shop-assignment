package edu.cit.caaway.channel;

import java.util.List;

/** One entry of the Tiangge order feed, already translated: sellerSku is our product ID. */
record FeedEvent(long seq, String eventId, String type, String orderId, List<Line> lines) {

    record Line(String productId, int quantity) {
    }

    /** One read of the feed: the events and the position to continue from. */
    record Page(List<FeedEvent> events, long nextCursor) {
    }
}
