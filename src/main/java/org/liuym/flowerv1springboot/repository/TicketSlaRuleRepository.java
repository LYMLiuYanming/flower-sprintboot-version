package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.TicketSlaRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SLA 规则（U14） */
public interface TicketSlaRuleRepository extends JpaRepository<TicketSlaRule, UUID> {

    /** 命中顺序：先按 (类型,优先级) 精确匹配启用中的规则 */
    Optional<TicketSlaRule> findByCategoryAndPriorityAndEnabled(String category, String priority, boolean enabled);

    /** 兜底规则：类型没有配置时退到 other，再退到任意启用规则，保证工单一定有 SLA 截止 */
    Optional<TicketSlaRule> findFirstByCategoryAndEnabled(String category, boolean enabled);

    List<TicketSlaRule> findAllByOrderByCategoryAscPriorityAsc();

    @Query("SELECT r FROM TicketSlaRule r WHERE r.enabled = true ORDER BY r.category ASC, r.priority ASC")
    List<TicketSlaRule> findEnabled();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TicketSlaRule r SET r.enabled = :enabled, r.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE r.id = :id AND r.enabled IS DISTINCT FROM :enabled")
    int updateEnabledCas(@Param("id") UUID id, @Param("enabled") boolean enabled);

    @Query("SELECT COUNT(r) FROM TicketSlaRule r WHERE r.enabled = true")
    long countEnabled();
}
