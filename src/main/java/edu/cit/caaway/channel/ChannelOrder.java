package edu.cit.caaway.channel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Links one Tiangge order to the one order it became in our Order module, and remembers what we
 * still owe Tiangge for it. The unique external ID is what makes a redelivered order harmless.
 */
@Entity
@Table(name = "channel_orders")
class ChannelOrder {

    enum State {
        /** Decided and saved, Tiangge has not confirmed receiving the decision yet. */
        DECISION_PENDING,
        /** Backordered: waiting for a supplier delivery. */
        WAITING_FOR_STOCK,
        /** Backorder filled or given up, Tiangge has not been told yet. */
        RESOLUTION_PENDING,
        /** Cancelled and restocked on our side, Tiangge has not been told yet. */
        CANCELLATION_PENDING,
        /** Nothing left to do. */
        DONE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_order_id", nullable = false, unique = true, length = 60)
    private String externalOrderId;

    @Column(name = "shop_order_id", nullable = false)
    private Long shopOrderId;

    // ACCEPTED, REJECTED or BACKORDERED: what we told (or will tell) Tiangge.
    @Column(nullable = false, length = 20)
    private String decision;

    @Column(length = 200)
    private String reason;

    // ACCEPTED or CANCELLED, set when a backorder ends.
    @Column(length = 20)
    private String resolution;

    // "PROD-001:2,PROD-002:1"
    @Column(nullable = false, length = 500)
    private String lines;

    @Column(nullable = false)
    private boolean cancelled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private State state;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ChannelOrder() {
    }

    ChannelOrder(String externalOrderId, Long shopOrderId, String decision, String reason, String lines) {
        this.externalOrderId = externalOrderId;
        this.shopOrderId = shopOrderId;
        this.decision = decision;
        this.reason = reason;
        this.lines = lines;
        this.state = State.DECISION_PENDING;
    }

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    Long getId() { return id; }
    String getExternalOrderId() { return externalOrderId; }
    Long getShopOrderId() { return shopOrderId; }
    String getDecision() { return decision; }
    String getReason() { return reason; }
    String getResolution() { return resolution; }
    String getLines() { return lines; }
    boolean isCancelled() { return cancelled; }
    State getState() { return state; }

    void setResolution(String resolution) { this.resolution = resolution; }
    void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    void setState(State state) { this.state = state; }
}
