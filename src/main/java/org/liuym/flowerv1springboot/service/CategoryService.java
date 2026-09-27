package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.dto.CategoryDtos;
import org.liuym.flowerv1springboot.model.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryService {

    Category save(Category category);

    List<Category> saveAll(List<Category> categories);

    Optional<Category> findById(UUID id);

    List<Category> findAll();

    List<Category> findRootCategories();

    List<Category> findChildCategories(UUID parentId);

    List<Category> findActiveCategories();

    Page<Category> findByPage(Pageable pageable);

    List<Category> searchByName(String name);

    Category update(Category category);

    boolean updateActiveStatus(UUID id, Boolean isActive);

    boolean updateSortOrder(UUID id, Integer sortOrder);

    boolean deleteById(UUID id);

    boolean existsByName(String name);

    long count();

    long countActive();

    /**
     * 后台新建分类：名称唯一，父级必须存在且不能自引用
     */
    Category createByForm(CategoryDtos.Form form);

    Category updateByForm(UUID id, CategoryDtos.Form form);

    /**
     * 删除分类：仍有商品挂在下时拒绝删除，避免商品失去归属
     */
    void deleteSafely(UUID id);
}