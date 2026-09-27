package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.model.Cart;
import org.liuym.flowerv1springboot.model.CartItem;

import java.util.UUID;

public interface CartService {

    Cart getCartByUserId(UUID userId);

    Cart addItem(UUID userId, UUID productId, Integer quantity);

    Cart updateItemQuantity(UUID userId, UUID cartItemId, Integer quantity);

    Cart removeItem(UUID userId, UUID cartItemId);

    Cart clearCart(UUID userId);

    int getCartItemCount(UUID userId);
}