package edu.cit.caaway.channel;

import org.springframework.data.jpa.repository.JpaRepository;

interface ChannelCursorRepository extends JpaRepository<ChannelCursor, String> {
}
