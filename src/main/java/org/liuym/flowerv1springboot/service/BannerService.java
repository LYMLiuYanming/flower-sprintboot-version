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

    /**
     * 前台轮播：仅取启用且在投放时间窗内的记录
     */
    List<Banner> findDisplayableBanners();

    Page<Banner> searchByTitle(String title, Pageable pageable);

    Banner createByForm(ContentDtos.BannerForm form);

    Banner updateByForm(UUID id, ContentDtos.BannerForm form);

    /**
     * 拖拽排序：ids 为页面上从上到下的新顺序，返回实际改动的行数
     */
    int resort(List<UUID> ids);
}
