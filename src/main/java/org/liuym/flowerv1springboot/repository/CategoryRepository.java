package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Category c SET c.isActive = :isActive, c.updatedAt = CURRENT_TIMESTAMP WHERE c.id = :id")
    int updateActiveStatus(@Param("id") UUID id, @Param("isActive") Boolean isActive);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Category c SET c.sortOrder = :sortOrder, c.updatedAt = CURRENT_TIMESTAMP WHERE c.id = :id")
    int updateSortOrder(@Param("id") UUID id, @Param("sortOrder") Integer sortOrder);
}