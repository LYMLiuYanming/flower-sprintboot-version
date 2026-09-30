package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.SafeUrl;
import org.liuym.flowerv1springboot.model.Favorite;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.repository.FavoriteRepository;
import org.liuym.flowerv1springboot.repository.ProductRepository;
import org.liuym.flowerv1springboot.repository.UserRepository;
import org.liuym.flowerv1springboot.service.FavoriteService;
import org.liuym.flowerv1springboot.vo.SupportViews.FavoriteView;
import org.liuym.flowerv1springboot.vo.ProductView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class FavoriteServiceImpl implements FavoriteService {

    private static final int MAX_PER_USER = 200;

    private final FavoriteRepository favoriteRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    public FavoriteServiceImpl(FavoriteRepository favoriteRepository,
                              ProductRepository productRepository,
                              UserRepository userRepository) {
        this.favoriteRepository = favoriteRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FavoriteView> list(UUID userId) {
        return FavoriteView.from(favoriteRepository.findByUserId(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FavoriteView> list(UUID userId, UUID categoryId, String sort, boolean inStock) {
        List<FavoriteView> all = FavoriteView.from(favoriteRepository.findByUserId(userId));
        return all.stream()
                .filter(f -> categoryId == null || (f.product() != null && f.product().category() != null
                        && categoryId.equals(f.product().category().id())))
                .filter(f -> !inStock || hasStock(f.product()))
                .sorted(comparator(sort))
                .toList();
    }

    @Override
    public boolean toggle(UUID userId, UUID productId) {
        return favoriteRepository.findByUserIdAndProductId(userId, productId)
                .map(existing -> {
                    favoriteRepository.delete(existing);
                    return false;
                })
                .orElseGet(() -> {
                    Product product = productRepository.findById(productId)
                            .orElseThrow(() -> BusinessException.notFound("商品不存在"));
                    if (favoriteRepository.countByUserId(userId) >= MAX_PER_USER) {
                        throw new BusinessException("收藏数量已达上限");
                    }
                    Favorite favorite = new Favorite();
                    favorite.setUser(userRepository.getReferenceById(userId));
                    favorite.setProduct(product);
                    favoriteRepository.save(favorite);
                    return true;
                });
    }

    @Override
    public int removeBatch(UUID userId, List<UUID> favoriteIds) {
        if (favoriteIds == null || favoriteIds.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (UUID id : favoriteIds) {
            // 归属校验：deleteByIdAndUserId 的 where 带 user_id，越权 id 影响行数为 0
            removed += favoriteRepository.deleteByIdAndUserId(id, userId);
        }
        return removed;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isFavorite(UUID userId, UUID productId) {
        return favoriteRepository.existsByUserIdAndProductId(userId, productId);
    }

    @Override
    @Transactional(readOnly = true)
    public long count(UUID userId) {
        return favoriteRepository.countByUserId(userId);
    }

    /** 有货优先：在售且库存>0（缺货或下架不计入「有货」） */
    private static boolean hasStock(ProductView p) {
        if (p == null) {
            return false;
        }
        boolean active = !Boolean.FALSE.equals(p.isActive());
        return active && p.stock() != null && p.stock() > 0;
    }

    /**
     * 排序比较器。下架/无商品的收藏永远沉底，价格类按现价、销量按累计销量、最新按收藏时间。
     */
    private static Comparator<FavoriteView> comparator(String sort) {
        Comparator<FavoriteView> offlineLast =
                Comparator.comparing((FavoriteView f) -> f.product() == null || Boolean.FALSE.equals(f.product().isActive()));
        Comparator<FavoriteView> bySort = switch (sort == null ? "newest" : sort) {
            case "price-asc" -> Comparator.comparing(FavoriteServiceImpl::priceOrMax);
            case "price-desc" -> Comparator.comparing(FavoriteServiceImpl::priceOrMax).reversed();
            case "sales" -> Comparator.comparing(FavoriteServiceImpl::salesOrZero).reversed();
            // newest 与收藏时间一致，findByUserId 已按 createdAt 倒序返回，这里再显式声明以防上游改动
            default -> Comparator.comparing(FavoriteView::createdAt, Comparator.nullsLast(Comparator.reverseOrder()));
        };
        return offlineLast.thenComparing(bySort);
    }

    private static BigDecimal priceOrMax(FavoriteView f) {
        ProductView p = f.product();
        return p == null || p.price() == null ? new BigDecimal("999999999") : p.price();
    }

    private static int salesOrZero(FavoriteView f) {
        ProductView p = f.product();
        return p == null || p.salesCount() == null ? 0 : p.salesCount();
    }
}
