package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.MessageTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 消息模板读写（U07） */
public interface MessageTemplateRepository extends JpaRepository<MessageTemplate, UUID> {

    Optional<MessageTemplate> findByCode(String code);

    boolean existsByCode(String code);

    Optional<MessageTemplate> findByCodeAndEnabled(String code, boolean enabled);

    @Query("""
            SELECT t FROM MessageTemplate t
            WHERE (:category = '' OR t.category = :category)
              AND (:enabledOnly = false OR t.enabled = true)
              AND (:keyword = '' OR LOWER(t.code) LIKE LOWER(CONCAT('%', :keyword, '%'))
                                 OR LOWER(t.titleTpl) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    Page<MessageTemplate> searchAdmin(@Param("category") String category,
                                      @Param("keyword") String keyword,
                                      @Param("enabledOnly") boolean enabledOnly,
                                      Pageable pageable);

    /** 停用/启用：条件带上「当前状态不等于目标状态」，影响行数即真实变更条数 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MessageTemplate t SET t.enabled = :enabled, t.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE t.id = :id AND t.enabled IS DISTINCT FROM :enabled")
    int updateEnabledCas(@Param("id") UUID id, @Param("enabled") boolean enabled);

    @Query("SELECT t.category, COUNT(t) FROM MessageTemplate t WHERE t.enabled = true GROUP BY t.category")
    List<Object[]> countEnabledByCategory();

    long countByEnabledTrue();
}
