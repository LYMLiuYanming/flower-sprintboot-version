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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    @Transactional(readOnly = true)
    public boolean isFavorite(UUID userId, UUID productId) {
        return favoriteRepository.existsByUserIdAndProductId(userId, productId);
    }

    @Override
    @Transactional(readOnly = true)
    public long count(UUID userId) {
        return favoriteRepository.countByUserId(userId);
    }
}
