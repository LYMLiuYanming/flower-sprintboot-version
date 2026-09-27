package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.dto.CategoryDtos;
import org.liuym.flowerv1springboot.model.Category;
import org.liuym.flowerv1springboot.repository.CategoryRepository;
import org.liuym.flowerv1springboot.repository.ProductRepository;
import org.liuym.flowerv1springboot.service.CategoryService;
import org.liuym.flowerv1springboot.config.CacheConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class CategoryServiceImpl implements CategoryService {

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Override
    @CacheEvict(cacheNames = CacheConfig.CATEGORIES, allEntries = true)
    public Category save(Category category) {
        if (category.getSortOrder() == null) {
            category.setSortOrder(0);
        }
        if (category.getIsActive() == null) {
            category.setIsActive(true);
        }
        return categoryRepository.save(category);
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.CATEGORIES, allEntries = true)
    public List<Category> saveAll(List<Category> categories) {
        return categoryRepository.saveAll(categories);
    }

    @Override
    public Optional<Category> findById(UUID id) {
        return categoryRepository.findById(id);
    }

    @Override
    public List<Category> findAll() {
        return categoryRepository.findAll();
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.CATEGORIES)
    public List<Category> findRootCategories() {
        return categoryRepository.findByParentIdIsNullOrderBySortOrderAsc();
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.CATEGORIES)
    public List<Category> findChildCategories(UUID parentId) {
        return categoryRepository.findByParentIdOrderBySortOrderAsc(parentId);
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.CATEGORIES)
    public List<Category> findActiveCategories() {
        return categoryRepository.findByIsActiveTrueOrderBySortOrderAsc();
    }

    @Override
    public Page<Category> findByPage(Pageable pageable) {
        return categoryRepository.findAll(pageable);
    }

    @Override
    public List<Category> searchByName(String name) {
        return categoryRepository.findByNameContaining(name);
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.CATEGORIES, allEntries = true)
    public Category update(Category category) {
        if (!categoryRepository.existsById(category.getId())) {
            throw new RuntimeException("分类不存在，更新失败！分类ID：" + category.getId());
        }
        return categoryRepository.save(category);
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.CATEGORIES, allEntries = true)
    public boolean updateActiveStatus(UUID id, Boolean isActive) {
        int affectedRows = categoryRepository.updateActiveStatus(id, isActive);
        return affectedRows > 0;
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.CATEGORIES, allEntries = true)
    public boolean updateSortOrder(UUID id, Integer sortOrder) {
        int affectedRows = categoryRepository.updateSortOrder(id, sortOrder);
        return affectedRows > 0;
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.CATEGORIES, allEntries = true)
    public boolean deleteById(UUID id) {
        if (categoryRepository.existsById(id)) {
            categoryRepository.deleteById(id);
            return true;
        }
        return false;
    }

    @Override
    public boolean existsByName(String name) {
        return categoryRepository.existsByName(name);
    }

    @Override
    public long count() {
        return categoryRepository.count();
    }

    @Override
    public long countActive() {
        return categoryRepository.findByIsActiveTrueOrderBySortOrderAsc().size();
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.CATEGORIES, allEntries = true)
    public Category createByForm(CategoryDtos.Form form) {
        if (categoryRepository.existsByName(form.name().trim())) {
            throw new BusinessException("分类名称「" + form.name().trim() + "」已存在");
        }
        Category category = new Category();
        applyForm(category, form);
        return categoryRepository.save(category);
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.CATEGORIES, allEntries = true)
    public Category updateByForm(UUID id, CategoryDtos.Form form) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("分类不存在"));
        categoryRepository.findByName(form.name().trim()).ifPresent(exist -> {
            if (!exist.getId().equals(id)) {
                throw new BusinessException("分类名称「" + form.name().trim() + "」已存在");
            }
        });
        applyForm(category, form);
        return categoryRepository.save(category);
    }

    private void applyForm(Category category, CategoryDtos.Form form) {
        if (form.parentId() != null) {
            if (form.parentId().equals(category.getId())) {
                throw new BusinessException("父分类不能是自身");
            }
            categoryRepository.findById(form.parentId())
                    .orElseThrow(() -> new BusinessException("所选父分类不存在"));
        }
        category.setParentId(form.parentId());
        category.setName(form.name().trim());
        category.setDescription(form.description());
        category.setIcon(form.icon());
        category.setSortOrder(form.sortOrder());
        category.setIsActive(form.isActive());
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.CATEGORIES, allEntries = true)
    public void deleteSafely(UUID id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("分类不存在"));
        if (categoryRepository.countByParentId(id) > 0) {
            throw new BusinessException("该分类下仍有子分类，请先删除子分类");
        }
        if (productRepository.countByCategoryId(id) > 0) {
            throw new BusinessException("该分类下仍有商品，请先转移或删除商品");
        }
        categoryRepository.delete(category);
    }
}