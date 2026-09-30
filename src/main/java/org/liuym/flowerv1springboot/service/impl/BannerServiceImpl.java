package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.SafeUrl;
import org.liuym.flowerv1springboot.config.CacheConfig;
import org.liuym.flowerv1springboot.dto.ContentDtos;
import org.liuym.flowerv1springboot.model.Banner;
import org.liuym.flowerv1springboot.repository.BannerRepository;
import org.liuym.flowerv1springboot.service.BannerService;
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
    public long countDisplayable() {
        return bannerRepository.countDisplayable(Banner.STATUS_ACTIVE, LocalDateTime.now());
    }

    @Override
    public int nextTopSortOrder() {
        // 间隔 10 与 resort 的口径一致，留出差值后新图可以直接插到任意两行之间
        return bannerRepository.maxSortOrder() + 10;
    }

    @Override
    public Page<Banner> searchByTitle(String title, Pageable pageable) {
        return bannerRepository.findByTitleContaining(title, pageable);
    }

    @Override
    public Page<Banner> searchAdmin(String status, String keyword, Pageable pageable) {
        return bannerRepository.searchAdmin(status == null ? "" : status,
                keyword == null ? "" : keyword.trim(), pageable);
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
        if (form.sortOrder() == null) {
            banner.setSortOrder(nextTopSortOrder());
        }
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

    /**
     * F13：跳转类型决定 link_target 的形态与最终 link_url 的拼法。
     * link_url 仍然落库，首页与旧后台列表继续读它，改版不影响现有页面。
     */
    private void applyForm(Banner banner, ContentDtos.BannerForm form) {
        if (form.startTime() != null && form.endTime() != null && form.endTime().isBefore(form.startTime())) {
            throw new BusinessException("投放结束时间不能早于开始时间");
        }
        String linkType = normalizeLinkType(form.linkType());
        String linkUrl = resolveLinkUrl(linkType, form);

        banner.setTitle(form.title().trim());
        banner.setImageUrl(safeUrl(form.imageUrl(), "图片地址"));
        banner.setThumbUrl(safeOptionalUrl(form.thumbUrl(), "缩略图地址"));
        banner.setLinkType(linkType);
        banner.setLinkTarget(linkTargetOf(linkType, form));
        banner.setLinkUrl(linkUrl);
        banner.setDescription(form.description() == null ? null : form.description().trim());
        banner.setSortOrder(form.sortOrder() == null ? 0 : form.sortOrder());
        banner.setStatus(form.status() == null ? Banner.STATUS_ACTIVE : form.status());
        banner.setStartTime(form.startTime());
        banner.setEndTime(form.endTime());
    }

    private static String normalizeLinkType(String linkType) {
        if (linkType == null || linkType.isBlank()) {
            return Banner.LINK_NONE;
        }
        String value = linkType.trim().toLowerCase();
        return switch (value) {
            case Banner.LINK_NONE, Banner.LINK_PRODUCT, Banner.LINK_CATEGORY, Banner.LINK_URL, Banner.LINK_PAGE -> value;
            default -> throw new BusinessException("跳转类型不合法：" + linkType);
        };
    }

    /** 目标只接受 UUID 的两种类型在这里统一校验，前台拼错一个字符也不会写出打不开的轮播 */
    private static String linkTargetOf(String linkType, ContentDtos.BannerForm form) {
        if (!Banner.LINK_PRODUCT.equals(linkType) && !Banner.LINK_CATEGORY.equals(linkType)) {
            return null;
        }
        String raw = form.linkTarget() == null ? "" : form.linkTarget().trim();
        try {
            return UUID.fromString(raw).toString();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(Banner.LINK_PRODUCT.equals(linkType)
                    ? "请选择要跳转的商品" : "请选择要跳转的分类");
        }
    }

    private static String resolveLinkUrl(String linkType, ContentDtos.BannerForm form) {
        return switch (linkType) {
            case Banner.LINK_NONE -> null;
            case Banner.LINK_PRODUCT -> "/product/" + linkTargetOf(linkType, form);
            case Banner.LINK_CATEGORY -> "/products?categoryId=" + linkTargetOf(linkType, form);
            case Banner.LINK_URL -> {
                String url = safeUrl(form.linkUrl(), "跳转链接");
                if (url == null || !url.startsWith("http://") && !url.startsWith("https://")) {
                    throw new BusinessException("外链需要填 http(s) 完整地址");
                }
                yield url;
            }
            default -> {
                String page = form.linkUrl() == null || form.linkUrl().isBlank()
                        ? form.linkTarget() : form.linkUrl();
                String url = safeUrl(page, "跳转链接");
                if (url == null || !url.startsWith("/")) {
                    throw new BusinessException("站内页面需要以 / 开头，例如 /products");
                }
                yield url;
            }
        };
    }

    private static String safeUrl(String value, String label) {
        try {
            String url = SafeUrl.requireSafe(value, label);
            if (url == null) {
                throw new BusinessException("请填写" + label);
            }
            return url;
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
    }

    private static String safeOptionalUrl(String value, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return SafeUrl.requireSafe(value, label);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
    }
}
