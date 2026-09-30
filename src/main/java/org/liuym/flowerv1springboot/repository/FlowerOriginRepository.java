package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.FlowerOrigin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FlowerOriginRepository extends JpaRepository<FlowerOrigin, UUID> {

    List<FlowerOrigin> findByKindAndIsActiveTrueOrderBySortOrderAsc(String kind);

    List<FlowerOrigin> findAllByOrderByKindAscSortOrderAsc();

    /** 后台列表：连停用的节点也要能看见并重新启用，所以不按 isActive 过滤 */
    List<FlowerOrigin> findByKindOrderBySortOrderAscNameAsc(String kind);

    List<FlowerOrigin> findByKindInAndIsActiveTrueOrderBySortOrderAsc(List<String> kinds);

    Optional<FlowerOrigin> findFirstByName(String name);

    boolean existsByNameAndKind(String name, String kind);

    /**
     * 下架而不是物理删除：产地一旦被商品或历史订单引用，硬删会留下孤儿外键，
     * 地图按 isActive 过滤后效果一致，所以后台「删除」统一走这里。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FlowerOrigin o SET o.isActive = false, o.updatedAt = :now WHERE o.id = :id")
    int retire(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /** 启用/停用：返回 0 表示状态本来就是目标值 */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FlowerOrigin o SET o.isActive = :active, o.updatedAt = :now WHERE o.id = :id")
    int updateActive(@Param("id") UUID id, @Param("active") boolean active, @Param("now") LocalDateTime now);

    /** 拖拽/排序维护：整体重排后一次落库 */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FlowerOrigin o SET o.sortOrder = :sort, o.updatedAt = :now WHERE o.id = :id")
    int updateSort(@Param("id") UUID id, @Param("sort") int sort, @Param("now") LocalDateTime now);

    /** 城市清单：后台新增节点时的「已有城市」下拉，避免同一城市重复建点 */
    @Query("SELECT DISTINCT o.city FROM FlowerOrigin o WHERE o.city IS NOT NULL AND o.isActive = true ORDER BY o.city")
    List<String> distinctActiveCities();
}
