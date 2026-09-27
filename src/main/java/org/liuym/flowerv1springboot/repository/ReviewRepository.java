package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    @Query("SELECT r FROM Review r JOIN FETCH r.user p JOIN FETCH r.product prod WHERE r.product.id = :productId "
            + "AND r.visible = true ORDER BY r.createdAt DESC")
    Page<Review> findVisibleByProductId(@Param("productId") UUID productId, Pageable pageable);

    @Query("SELECT r FROM Review r JOIN FETCH r.product prod JOIN FETCH r.user u WHERE r.order.id = :orderId ORDER BY r.createdAt")
    List<Review> findByOrderId(@Param("orderId") UUID orderId);

    @Query("SELECT r FROM Review r JOIN FETCH r.product prod WHERE r.user.id = :userId ORDER BY r.createdAt DESC")
    List<Review> findByUserId(@Param("userId") UUID userId);

    boolean existsByOrderItemIdAndProductId(UUID orderItemId, UUID productId);

    @Query("SELECT r.orderItem.id FROM Review r WHERE r.order.id IN :orderIds")
    List<UUID> findReviewedItemIds(@Param("orderIds") java.util.Collection<UUID> orderIds);

    @Query("SELECT COALESCE(AVG(r.rating), 5), COUNT(r.id) FROM Review r WHERE r.product.id = :productId AND r.visible = true")
    List<Object[]> ratingSummary(@Param("productId") UUID productId);
}
