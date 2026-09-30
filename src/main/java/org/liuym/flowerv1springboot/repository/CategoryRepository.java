package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    List<Category> findByParentIdIsNullOrderBySortOrderAsc();

    List<Category> findByParentIdOrderBySortOrderAsc(UUID parentId);

    List<Category> findByIsActiveTrueOrderBySortOrderAsc();

    Page<Category> findByIsActive(Boolean isActive, Pageable pageable);

    List<Category> findByNameContaining(String name);

    /** 输入联想用：命中的启用分类名 */
    @Query("""
            SELECT c.name FROM Category c
            WHERE c.isActive = true AND LOWER(c.name) LIKE LOWER(CONCAT('%', :term, '%'))
            ORDER BY c.sortOrder ASC
            """)
    List<String> findNamesMatching(@Param("term") String term, Pageable pageable);

    Optional<Category> findByName(String name);

    boolean existsByName(String name);

    long countByParentId(UUID parentId);

    /** 本分类 + 其直接子分类：分类筛选与分类专享券都按这一口径覆盖下级 */
    default List<UUID> idsWithChildren(UUID categoryId) {
        List<UUID> ids = new ArrayList<>();
        ids.add(categoryId);
        findByParentIdOrderBySortOrderAsc(categoryId).forEach(child -> ids.add(child.getId()));
        return ids;
    }

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Category c SET c.isActive = :isActive, c.updatedAt = CURRENT_TIMESTAMP WHERE c.id = :id")
    int updateActiveStatus(@Param("id") UUID id, @Param("isActive") Boolean isActive);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Category c SET c.sortOrder = :sortOrder, c.updatedAt = CURRENT_TIMESTAMP WHERE c.id = :id")
    int updateSortOrder(@Param("id") UUID id, @Param("sortOrder") Integer sortOrder);

    /**
     * 拖拽排序（G13）用的条件更新：只有排序值仍是读到的旧值才写入。
     * 两个人同时在排同一批分类时，后提交的那个会得到 0 行而不是把别人的顺序盖掉
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Category c SET c.sortOrder = :targetOrder, c.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE c.id = :id AND c.sortOrder = :expectedOrder")
    int updateSortOrderIfUnchanged(@Param("id") UUID id,
                                   @Param("expectedOrder") Integer expectedOrder,
                                   @Param("targetOrder") Integer targetOrder);
}