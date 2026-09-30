package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.PromotionSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PromotionSlotRepository extends JpaRepository<PromotionSlot, UUID> {

    List<PromotionSlot> findAllByOrderByPositionAscSortOrderAsc();

    List<PromotionSlot> findByPositionOrderBySortOrderAsc(String position);

    /**
     * 前台可见位：开关打开且在投放窗口内，排序由后台维护
     */
    @Query("""
            SELECT s FROM PromotionSlot s
            WHERE s.position = :position AND s.status = 'active'
              AND (s.startTime IS NULL OR s.startTime <= :now)
              AND (s.endTime IS NULL OR s.endTime >= :now)
            ORDER BY s.sortOrder ASC, s.createdAt ASC
            """)
    List<PromotionSlot> findVisible(@Param("position") String position, @Param("now") LocalDateTime now);

    /** 开关：条件更新避免与并发的另一次点击互相覆盖成旧值 */
    @Modifying
    @Query("""
            UPDATE PromotionSlot s SET s.status = :target, s.updatedAt = CURRENT_TIMESTAMP
            WHERE s.id = :id AND s.status <> :target
            """)
    int updateStatus(@Param("id") UUID id, @Param("target") String target);

    @Modifying
    @Query("UPDATE PromotionSlot s SET s.sortOrder = :sortOrder, s.updatedAt = CURRENT_TIMESTAMP WHERE s.id = :id")
    int updateSortOrder(@Param("id") UUID id, @Param("sortOrder") Integer sortOrder);

    Optional<PromotionSlot> findByCouponId(UUID couponId);
}
