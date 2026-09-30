package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.dto.CategoryDtos;
import org.liuym.flowerv1springboot.model.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** 删除前的影响面：商品数（含子分类）、子分类数、能否删除与处置建议（A25） */
    Map<String, Object> deleteImpact(UUID id);

    /** 每个分类直接挂载的商品数，后台列表一次取回避免逐行 count */
    Map<UUID, Long> productCountByCategory();

    /**
     * 后台层级序（G14）：父分类在前、其子分类紧随，同层按 sortOrder 升序。
     * 只支持一层父子，所以直接两段拼接，不用递归也不用树形控件
     */
    List<Category> treeOrdered();

    /**
     * 拖拽排序（G13）：把给定这一批分类的 sortOrder 值按新顺序重新分配。
     * 只在拖动集合内部换位，集合外的分类排序值不动，前后台都不会因此错乱
     *
     * @return 实际写入的分类数
     */
    int reorder(List<UUID> orderedIds);

    /** 每个分类的在售商品数：前台分类瓦片显示「在售 N 款」，同样是取回而不是逐行 count */
    Map<UUID, Long> activeProductCountByCategory();

    /**
     * A25：把「不能删」翻译成「怎么做才能删」。
     * 可删时返回 null，运营在弹窗里看到的就是下一步动作而不是一个冷冰冰的禁止
     */
    static String guardHint(String name, long productCount, long childCount) {
        if (productCount > 0 && childCount > 0) {
            return "「" + name + "」下挂 " + productCount + " 款商品、" + childCount
                    + " 个子分类：先到商品管理把商品改挂到其他分类，再删除子分类，最后才能删除本分类。";
        }
        if (productCount > 0) {
            return "「" + name + "」下挂 " + productCount
                    + " 款商品：可在商品管理批量改挂分类，或只把本分类「停用」，前台导航即刻隐藏。";
        }
        if (childCount > 0) {
            return "「" + name + "」还有 " + childCount
                    + " 个子分类：请先处理子分类（改挂父级或删除），或把本分类「停用」隐藏入口。";
        }
        return null;
    }

    /** 建议动作清单：弹窗按钮文案由它生成，与 guardHint 同一判定口径 */
    static List<String> guardActions(long productCount, long childCount) {
        Map<String, String> actions = new LinkedHashMap<>();
        if (productCount > 0) {
            actions.put("reassign", "去商品管理改挂分类");
            actions.put("deactivate", "改为停用本分类");
        }
        if (childCount > 0) {
            actions.put("children", "先处理子分类");
            actions.put("deactivate", "改为停用本分类");
        }
        return List.copyOf(actions.values());
    }
}