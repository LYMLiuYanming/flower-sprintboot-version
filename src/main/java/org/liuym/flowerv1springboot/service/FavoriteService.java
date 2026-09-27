package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.vo.SupportViews.FavoriteView;

import java.util.List;
import java.util.UUID;

public interface FavoriteService {

    List<FavoriteView> list(UUID userId);

    /** 收藏/取消收藏切换，返回切换后的状态 */
    boolean toggle(UUID userId, UUID productId);

    boolean isFavorite(UUID userId, UUID productId);

    long count(UUID userId);
}
