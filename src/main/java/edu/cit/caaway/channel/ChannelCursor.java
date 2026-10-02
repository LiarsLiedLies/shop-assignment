package edu.cit.caaway.channel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** How far we have read the Tiangge feed. Stored in the database so a restart continues from here. */
@Entity
@Table(name = "channel_cursor")
class ChannelCursor {

    static final String FEED = "tiangge-feed";

    @Id
    @Column(length = 40)
    private String name;

    @Column(name = "feed_position", nullable = false)
    private long position;

    protected ChannelCursor() {
    }

    ChannelCursor(String name, long position) {
        this.name = name;
        this.position = position;
    }

    long getPosition() { return position; }
    void setPosition(long position) { this.position = position; }
}
