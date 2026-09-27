package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.SafeUrl;
import org.liuym.flowerv1springboot.dto.ContentDtos;
import org.liuym.flowerv1springboot.model.Banner;
import org.liuym.flowerv1springboot.repository.BannerRepository;
import org.liuym.flowerv1springboot.service.BannerService;
import org.liuym.flowerv1springboot.config.CacheConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class BannerServiceImpl implements BannerService {

    @Autowired
    private BannerRepository bannerRepository;

    @Override
    @CacheEvict(cacheNames = CacheConfig.BANNERS, allEntries = true)
    public Banner save(Banner banner) {
        return bannerRepository.save(banner);
    }

    @Override
    public Optional<Banner> findById(UUID id) {
        return bannerRepository.findById(id);
    }

    @Override
    public Page<Banner> findAll(Pageable pageable) {
        return bannerRepository.findAll(pageable);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.BANNERS, allEntries = true)
    public boolean updateStatus(UUID id, String status) {
        int affectedRows = bannerRepository.updateStatus(id, status);
        return affectedRows > 0;
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.BANNERS, allEntries = true)
    public boolean deleteById(UUID id) {
        if (bannerRepository.existsById(id)) {
            bannerRepository.deleteById(id);
            return true;
        }
        return false;
    }

    @Override
    public long count() {
        return bannerRepository.count();
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.BANNERS)
    public List<Banner> findDisplayableBanners() {
        return bannerRepository.findDisplayable(Banner.STATUS_ACTIVE, LocalDateTime.now());
    }

    @Override
    public Page<Banner> searchByTitle(String title, Pageable pageable) {
        return bannerRepository.findByTitleContaining(title, pageable);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.BANNERS, allEntries = true)
    public int resort(List<UUID> ids) {
        Map<UUID, Banner> bannersById = bannerRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Banner::getId, banner -> banner));
        int total = ids.size();
        List<Banner> changed = new ArrayList<>();
        for (int index = 0; index < total; index++) {
            Banner banner = bannersById.get(ids.get(index));
            if (banner == null) {
                continue;
            }
            // 列表按 sortOrder 倒序展示，第一行取最大值；间隔 10 便于后续插入新图
            int next = (total - index) * 10;
            if (!Objects.equals(banner.getSortOrder(), next)) {
                banner.setSortOrder(next);
                changed.add(banner);
            }
        }
        if (changed.isEmpty()) {
            return 0;
        }
        bannerRepository.saveAll(changed);
        return changed.size();
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.BANNERS, allEntries = true)
    public Banner createByForm(ContentDtos.BannerForm form) {
        Banner banner = new Banner();
        applyForm(banner, form);
        return bannerRepository.save(banner);
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.BANNERS, allEntries = true)
    public Banner updateByForm(UUID id, ContentDtos.BannerForm form) {
        Banner banner = bannerRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("轮播图不存在"));
        applyForm(banner, form);
        return bannerRepository.save(banner);
    }

    private void applyForm(Banner banner, ContentDtos.BannerForm form) {
        if (form.startTime() != null && form.endTime() != null && form.endTime().isBefore(form.startTime())) {
            throw new BusinessException("投放结束时间不能早于开始时间");
        }
        banner.setTitle(form.title().trim());
        banner.setImageUrl(SafeUrl.requireSafe(form.imageUrl(), "图片地址"));
        banner.setLinkUrl(SafeUrl.requireSafe(form.linkUrl(), "跳转链接"));
        banner.setDescription(form.description());
        banner.setSortOrder(form.sortOrder());
        banner.setStatus(form.status() == null ? Banner.STATUS_ACTIVE : form.status());
        banner.setStartTime(form.startTime());
        banner.setEndTime(form.endTime());
    }
}
