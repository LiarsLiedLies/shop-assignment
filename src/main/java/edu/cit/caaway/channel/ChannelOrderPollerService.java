package edu.cit.caaway.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the Tiangge order feed every few seconds. The position is stored in the database after
 * every batch, so a restarted app continues where the previous run stopped.
 */
@Component
class ChannelOrderPollerService {

    private static final Logger log = LoggerFactory.getLogger(ChannelOrderPollerService.class);

    private static final int BATCH_SIZE = 50;
    private static final int MAX_BATCHES_PER_ROUND = 10;
    // An event that keeps failing must not block every order behind it for ever.
    private static final int MAX_TRIES_PER_EVENT = 3;

    private final ChannelStartupRunner startup;
    private final TianggeClient client;
    private final ChannelOrderProcessor processor;
    private final ChannelCursorRepository cursors;
    private final Map<String, Integer> failedTries = new ConcurrentHashMap<>();

    ChannelOrderPollerService(ChannelStartupRunner startup, TianggeClient client, ChannelOrderProcessor processor,
                              ChannelCursorRepository cursors) {
        this.startup = startup;
        this.client = client;
        this.processor = processor;
        this.cursors = cursors;
    }

    @Scheduled(fixedDelayString = "${tiangge.poll-interval-ms:3000}", initialDelay = 2000)
    void poll() {
        if (!startup.goLiveIfNeeded()) {
            return;
        }
        try {
            processor.resendUnsent();
            processor.saveDeliveredDecisions();
            processor.resolveBackorders();
            readFeed();
        } catch (TianggeUnavailableException e) {
            log.warn("Tiangge not reachable, trying again next round: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("Feed round failed, trying again next round", e);
        }
    }

    private void readFeed() {
        ChannelCursor cursor = cursors.findById(ChannelCursor.FEED)
                .orElseGet(() -> new ChannelCursor(ChannelCursor.FEED, 0));

        for (int batch = 0; batch < MAX_BATCHES_PER_ROUND; batch++) {
            FeedEvent.Page page = client.readFeed(cursor.getPosition(), BATCH_SIZE);
            long position = cursor.getPosition();
            boolean stopped = false;

            for (FeedEvent event : page.events()) {
                try {
                    processor.handle(event);
                    failedTries.remove(event.eventId());
                } catch (RuntimeException e) {
                    int tries = failedTries.merge(event.eventId(), 1, Integer::sum);
                    if (tries < MAX_TRIES_PER_EVENT) {
                        log.warn("Feed event {} ({}) failed, try {}: {}", event.eventId(), event.orderId(), tries, e.toString());
                        stopped = true;
                        break;
                    }
                    log.error("Feed event {} ({}) skipped after {} tries", event.eventId(), event.orderId(), tries, e);
                }
                position = event.seq();
            }
            processor.saveDeliveredDecisions();
            if (!stopped) {
                position = Math.max(position, page.nextCursor());
            }
            if (position != cursor.getPosition()) {
                cursor.setPosition(position);
                cursor = cursors.save(cursor);
            }
            if (stopped || page.events().size() < BATCH_SIZE) {
                return;
            }
        }
    }
}
