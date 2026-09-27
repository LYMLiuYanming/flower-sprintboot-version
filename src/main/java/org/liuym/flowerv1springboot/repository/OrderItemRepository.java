package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    List<OrderItem> findByOrderId(UUID orderId);

    /** 商品是否被历史订单引用，决定删除还是下架归档 */
    boolean existsByProduct_Id(UUID productId);
}