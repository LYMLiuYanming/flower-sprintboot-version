package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.dto.ContentDtos;
import org.liuym.flowerv1springboot.model.Banner;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BannerService {

    Banner save(Banner banner);

    Optional<Banner> findById(UUID id);

    Page<Banner> findAll(Pageable pageable);

    boolean updateStatus(UUID id, String status);

    boolean deleteById(UUID id);

    long count();

    /** 当前排最前的排序值，新增时 +10 就是「插到第一位」（F14） */
    int nextTopSortOrder();

    /**
     * 前台轮播：仅取启用且在投放时间窗内的记录
     */
    List<Banner> findDisplayableBanners();

    /** F13/F14 前台可见数量：后台用它提示「当前前台播放 N 张」 */
    long countDisplayable();

    Page<Banner> searchByTitle(String title, Pageable pageable);

    Page<Banner> searchAdmin(String status, String keyword, Pageable pageable);

    Banner createByForm(ContentDtos.BannerForm form);

    Banner updateByForm(UUID id, ContentDtos.BannerForm form);

    /**
     * 拖拽排序：ids 为页面上从上到下的新顺序，返回实际改动的行数
     */
    int resort(List<UUID> ids);
}
