package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.vo.SupportViews.FavoriteView;

import java.util.List;
import java.util.UUID;

public interface FavoriteService {

    List<FavoriteView> list(UUID userId);

    /**
     * 带筛选与排序的收藏列表（D11/D12）。
     *
     * @param categoryId 分类过滤，null 或 "all" 表示不过滤
     * @param sort       排序：newest（默认，收藏时间倒序）/ price-asc / price-desc / sales（销量降序）
     * @param inStock    为 true 时只留有货且在售的商品（D12「有货优先」过滤）
     */
    List<FavoriteView> list(UUID userId, UUID categoryId, String sort, boolean inStock);

    /** 收藏/取消收藏切换，返回切换后的状态 */
    boolean toggle(UUID userId, UUID productId);

    /** 批量取消收藏，返回实际删除条数 */
    int removeBatch(UUID userId, List<UUID> favoriteIds);

    boolean isFavorite(UUID userId, UUID productId);

    long count(UUID userId);
}
