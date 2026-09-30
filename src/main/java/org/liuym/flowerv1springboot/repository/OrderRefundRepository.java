package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.OrderRefund;
import org.liuym.flowerv1springboot.model.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface OrderRefundRepository extends JpaRepository<OrderRefund, UUID> {

    @Query("SELECT r FROM OrderRefund r JOIN FETCH r.order WHERE r.order.id = :orderId ORDER BY r.createdAt DESC")
    List<OrderRefund> findByOrderIdHistory(@Param("orderId") UUID orderId);

    /** 在途申请：详情页与售后待办共用，部分唯一索引保证一笔单最多一条 */
    @Query("SELECT r FROM OrderRefund r WHERE r.order.id = :orderId AND r.status IN :open ORDER BY r.createdAt DESC")
    List<OrderRefund> findOpenOfOrder(@Param("orderId") UUID orderId, @Param("open") List<RefundStatus> open);

    /**
     * 退款单状态跃迁（C19）：一条语句里带上「期望状态」做 CAS，并按目标状态补时间戳。
     * COALESCE 让「受理」与「打款」共用同一条语句，第二个人并发审核只会命中 0 行。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OrderRefund r SET r.status = :target, "
            + "r.reviewer = COALESCE(:reviewer, r.reviewer), r.reviewNote = COALESCE(:note, r.reviewNote), "
            + "r.acceptedAt = COALESCE(:acceptedAt, r.acceptedAt), r.settledAt = COALESCE(:settledAt, r.settledAt), "
            + "r.updatedAt = :now WHERE r.id = :id AND r.status = :expected")
    int transit(@Param("id") UUID id,
                @Param("expected") RefundStatus expected,
                @Param("target") RefundStatus target,
                @Param("reviewer") String reviewer,
                @Param("note") String note,
                @Param("acceptedAt") LocalDateTime acceptedAt,
                @Param("settledAt") LocalDateTime settledAt,
                @Param("now") LocalDateTime now);
}
