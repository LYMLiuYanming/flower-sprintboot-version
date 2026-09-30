package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.model.Cart;

import java.util.UUID;

/**
 * 购物车服务：返回的 Cart 已经过服务端钳制，notice 是给前端的同源文案（B03/B04/B06）。
 */
public interface CartService {

    /** 一次改动的结果：购物车 + 需要提示用户的话（无提示为 null） */
    record Change(Cart cart, String notice) {

        public static Change of(Cart cart) {
            return new Change(cart, null);
        }

        public static Change of(Cart cart, String notice) {
            return new Change(cart, notice);
        }
    }

    Cart getCartByUserId(UUID userId);

    Change addItem(UUID userId, UUID productId, Integer quantity);

    /** 数量 / 礼品包装 / 行备注一次改完（B01/B02/B03），null 表示该维度不动 */
    Change updateItem(UUID userId, UUID cartItemId, Integer quantity, Boolean giftWrap, String note);

    Change removeItem(UUID userId, UUID cartItemId);

    /** 移入收藏并删除该行（B04） */
    Change moveToFavorites(UUID userId, UUID cartItemId);

    /** 清空失效商品（B06），返回被清掉的行数 */
    Change removeInvalid(UUID userId);

    Change clearCart(UUID userId);

    int getCartItemCount(UUID userId);
}
