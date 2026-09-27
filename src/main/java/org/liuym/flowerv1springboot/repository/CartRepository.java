package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.Cart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CartRepository extends JpaRepository<Cart, UUID> {

    /**
     * 一次取全购物车 + 明细 + 商品/分类，open-in-view 关闭后视图渲染不再触发懒加载
     */
    @Query("SELECT DISTINCT c FROM Cart c LEFT JOIN FETCH c.items i LEFT JOIN FETCH i.product p LEFT JOIN FETCH p.category "
            + "WHERE c.user.id = :userId")
    Optional<Cart> findByUserIdWithItems(@Param("userId") UUID userId);

    Optional<Cart> findByUserId(UUID userId);
}
