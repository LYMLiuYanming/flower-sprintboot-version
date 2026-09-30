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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Transactional
public class CategoryServiceImpl implements CategoryService {

    /** 图标只接受 Font Awesome 类名后缀（去掉 fa- / fas 前缀之后）：挡住把脚本或外部链接塞进图标字段 */
    private static final Pattern ICON_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{0,39}$");

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
        if (!categoryRepository.existsById(id)) {
            return false;
        }
        // 统一走守卫：留一条无校验的删除口，商品就会挂在不存在的分类上
        deleteSafely(id);
        return true;
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
            Category parent = categoryRepository.findById(form.parentId())
                    .orElseThrow(() -> new BusinessException("所选父分类不存在"));
            // 挂载商品数与分类筛选都按「本分类 + 直接子分类」一层口径统计（见 CategoryRepository.idsWithChildren），
            // 挂到第三层就会统计不到，所以这里锁死两级
            if (parent.getParentId() != null) {
                throw new BusinessException("分类只支持两级：「" + parent.getName() + "」本身已经是子分类");
            }
            // 已经有子分类的节点再往下挂，整条链就变成三层了
            if (category.getId() != null && categoryRepository.countByParentId(category.getId()) > 0) {
                throw new BusinessException("「" + category.getName() + "」下已有子分类，不能再挂到其它分类下");
            }
        }
        category.setParentId(form.parentId());
        category.setName(form.name().trim());
        category.setDescription(trimOrNull(form.description(), 200));
        category.setIcon(normalizeIcon(form.icon()));
        category.setSortOrder(form.sortOrder());
        category.setIsActive(form.isActive());
    }

    @Override
    public List<Category> treeOrdered() {
        List<Category> all = categoryRepository.findAll();
        Map<UUID, List<Category>> childrenByParent = new LinkedHashMap<>();
        Set<UUID> known = new HashSet<>();
        all.forEach(category -> known.add(category.getId()));
        for (Category category : all) {
            if (category.getParentId() != null && known.contains(category.getParentId())) {
                childrenByParent.computeIfAbsent(category.getParentId(), key -> new ArrayList<>()).add(category);
            }
        }
        Comparator<Category> bySortOrder = Comparator.comparing(Category::getSortOrder,
                Comparator.nullsLast(Comparator.naturalOrder()));
        childrenByParent.values().forEach(list -> list.sort(bySortOrder));
        List<Category> ordered = new ArrayList<>(all.size());
        all.stream()
                .filter(category -> category.getParentId() == null || !known.contains(category.getParentId()))
                .sorted(bySortOrder)
                .forEach(root -> {
                    ordered.add(root);
                    // parentId 指向已被删除的分类同样按顶级处理，否则这一条会在层级序里凭空消失
                    ordered.addAll(childrenByParent.getOrDefault(root.getId(), List.of()));
                });
        return ordered;
    }

    /**
     * 拖拽排序（G13）：新顺序只重排这批分类原本占用的排序值槽位，
     * 集合外的分类位置保持不变，不会出现整表重排把别人的排序意图冲掉
     */
    @Override
    @CacheEvict(cacheNames = CacheConfig.CATEGORIES, allEntries = true)
    public int reorder(List<UUID> orderedIds) {
        if (orderedIds == null || orderedIds.size() < 2) {
            throw new BusinessException("至少拖动两个分类才能保存顺序");
        }
        if (orderedIds.size() > 200) {
            throw new BusinessException("一次最多重排 200 个分类，请分页操作");
        }
        List<Category> loaded = categoryRepository.findAllById(orderedIds);
        if (loaded.size() != orderedIds.size()) {
            throw new BusinessException("有分类已被他人删除，请刷新列表后重新拖动");
        }
        List<Integer> slots = loaded.stream().map(Category::getSortOrder).sorted().toList();
        Map<UUID, Integer> current = new HashMap<>();
        loaded.forEach(category -> current.put(category.getId(), category.getSortOrder()));
        int affected = 0;
        for (int index = 0; index < orderedIds.size(); index++) {
            UUID id = orderedIds.get(index);
            Integer target = slots.get(index);
            Integer expected = current.get(id);
            if (target == null || target.equals(expected)) {
                continue;
            }
            // 条件更新：别人在我们拖动期间改过这条时影响行数为 0，本次跳过而不是硬覆盖
            affected += categoryRepository.updateSortOrderIfUnchanged(id, expected, target);
        }
        return affected;
    }

    /**
     * A24：图标名只留 Font Awesome 类名的后缀（leaf / sweetpea），页面按 'fa fa-' + icon 拼类名。
     * 运营常整串粘贴「fa-leaf」或「fas fa-leaf」，这里一并纠偏；含尖括号等非法字符直接拒收，
     * 避免图标字段变成注入 class 甚至 HTML 的入口
     */
    static String normalizeIcon(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String icon = raw.trim().toLowerCase();
        icon = icon.replaceFirst("^(?:far|fas|fab|fa)\\s+", "");
        icon = icon.replaceFirst("^fa-", "");
        if (icon.isEmpty()) {
            return null;
        }
        if (icon.length() > 40 || !ICON_PATTERN.matcher(icon).matches()) {
            throw new BusinessException("图标名只允许字母、数字与连字符，例如 leaf");
        }
        return icon;
    }

    /** 长度截断在 DTO 已校验，这里只负责把「空白」统一成 null，别让前端拿到 " " 去 split */
    private static String trimOrNull(String value, int maxLen) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLen) {
            throw new BusinessException("内容过长，请精简到 " + maxLen + " 字以内");
        }
        return trimmed;
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.CATEGORIES, allEntries = true)
    public void deleteSafely(UUID id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("分类不存在"));
        // 商品数按「本分类 + 子分类」统计：只查直接挂载会漏掉子代，删完之后商品就挂在已消失的分类上
        List<UUID> scope = categoryRepository.idsWithChildren(id);
        long productCount = productRepository.countByCategoryIdIn(scope);
        long childCount = categoryRepository.countByParentId(id);
        String hint = CategoryService.guardHint(category.getName(), productCount, childCount);
        if (hint != null) {
            throw new BusinessException(hint);
        }
        categoryRepository.delete(category);
    }

    @Override
    public Map<String, Object> deleteImpact(UUID id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("分类不存在"));
        List<UUID> scope = categoryRepository.idsWithChildren(id);
        long productCount = productRepository.countByCategoryIdIn(scope);
        long activeProductCount = productRepository.countActiveByCategory(id);
        long childCount = categoryRepository.countByParentId(id);
        String hint = CategoryService.guardHint(category.getName(), productCount, childCount);
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("id", category.getId());
        impact.put("name", category.getName());
        impact.put("productCount", productCount);
        impact.put("activeProductCount", activeProductCount);
        impact.put("childCount", childCount);
        impact.put("deletable", hint == null);
        impact.put("hint", hint == null ? "该分类当前没有挂载商品与子分类，可以删除。" : hint);
        impact.put("actions", CategoryService.guardActions(productCount, childCount));
        return impact;
    }

    @Override
    public Map<UUID, Long> productCountByCategory() {
        return toCounts(productRepository.countGroupedByCategory());
    }

    @Override
    public Map<UUID, Long> activeProductCountByCategory() {
        return toCounts(productRepository.countActiveGroupedByCategory());
    }

    /** JPQL 分组结果转 Map：分类被删除后商品计数不会带出 null 键，这里仍做一次防御 */
    private static Map<UUID, Long> toCounts(List<Object[]> rows) {
        Map<UUID, Long> counts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            if (row[0] != null) {
                counts.put((UUID) row[0], ((Number) row[1]).longValue());
            }
        }
        return counts;
    }
}