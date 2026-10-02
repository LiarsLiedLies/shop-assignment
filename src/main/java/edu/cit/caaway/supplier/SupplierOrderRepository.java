package edu.cit.caaway.supplier;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

interface SupplierOrderRepository extends JpaRepository<SupplierOrder, Long> {

    List<SupplierOrder> findByStatusOrderByIdAsc(ReorderStatus status);

    List<SupplierOrder> findByStatusInOrderByIdAsc(Collection<ReorderStatus> statuses);

    List<SupplierOrder> findByProductIdAndStatusInOrderByIdAsc(String productId, Collection<ReorderStatus> statuses);
}
