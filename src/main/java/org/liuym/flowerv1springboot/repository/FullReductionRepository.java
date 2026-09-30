package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.FullReduction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FullReductionRepository extends JpaRepository<FullReduction, UUID> {

    List<FullReduction> findAllByOrderByPriorityDescCreatedAtDesc();

    List<FullReduction> findByStatusOrderByPriorityDesc(String status);

    /**
     * 生效中的活动：窗口过滤放在 SQL 里，避免把过期活动拉进内存再判一次
     */
    @Query("""
            SELECT f FROM FullReduction f
            WHERE f.status = 'active'
              AND (f.startTime IS NULL OR f.startTime <= :now)
              AND (f.endTime IS NULL OR f.endTime >= :now)
            ORDER BY f.priority DESC, f.createdAt ASC
            """)
    List<FullReduction> findActive(@Param("now") LocalDateTime now);

    /** 启停用条件更新：只有当前状态与期望一致才改写，重复点击不会互相覆盖 */
    @Modifying
    @Query("""
            UPDATE FullReduction f SET f.status = :target, f.updatedAt = CURRENT_TIMESTAMP
            WHERE f.id = :id AND f.status <> :target
            """)
    int updateStatus(@Param("id") UUID id, @Param("target") String target);
}
