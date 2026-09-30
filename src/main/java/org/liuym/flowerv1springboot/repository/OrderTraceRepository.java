package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.OrderTrace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OrderTraceRepository extends JpaRepository<OrderTrace, UUID> {

    @Query("SELECT t FROM OrderTrace t WHERE t.order.id = :orderId ORDER BY t.createdAt ASC, t.id ASC")
    List<OrderTrace> findByOrderId(@Param("orderId") UUID orderId);
}
