package edu.cit.caaway.channel;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Tells Tiangge every 30 seconds that this copy of the app is alive. */
@Component
class ChannelHeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(ChannelHeartbeatService.class);

    private final ChannelContext context;
    private final TianggeClient client;
    private final Instant startedAt = Instant.now();

    ChannelHeartbeatService(ChannelContext context, TianggeClient client) {
        this.context = context;
        this.client = client;
    }

    // Runs while the application is still starting, so the heartbeat is the first call Tiangge sees.
    @PostConstruct
    void announce() {
        log.info("Instance ID for this run: {}", context.getInstanceId());
        beat();
    }

    @Scheduled(fixedRate = 30000, initialDelay = 30000)
    void beat() {
        try {
            long uptime = Duration.between(startedAt, Instant.now()).getSeconds();
            client.heartbeat("ShopApplication", startedAt.toString(), uptime);
        } catch (RuntimeException e) {
            log.warn("Heartbeat failed: {}", e.getMessage());
        }
    }
}
