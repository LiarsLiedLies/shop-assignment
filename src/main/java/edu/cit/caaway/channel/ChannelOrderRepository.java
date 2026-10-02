package edu.cit.caaway.channel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface ChannelOrderRepository extends JpaRepository<ChannelOrder, Long> {

    Optional<ChannelOrder> findByExternalOrderId(String externalOrderId);

    List<ChannelOrder> findByStateOrderByIdAsc(ChannelOrder.State state);

    List<ChannelOrder> findByStateInOrderByIdAsc(Collection<ChannelOrder.State> states);

    /** Moves many orders on in one statement; only rows still in the expected state are touched. */
    @Modifying
    @Query("update ChannelOrder o set o.state = :next, o.updatedAt = local datetime where o.id in :ids and o.state = :expected")
    int advance(@Param("ids") Collection<Long> ids, @Param("expected") ChannelOrder.State expected,
                @Param("next") ChannelOrder.State next);
}
