package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.CheckoutPolicy;
import org.liuym.flowerv1springboot.model.Cart;
import org.liuym.flowerv1springboot.model.CartItem;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.repository.CartItemRepository;
import org.liuym.flowerv1springboot.repository.CartRepository;
import org.liuym.flowerv1springboot.repository.ProductRepository;
import org.liuym.flowerv1springboot.repository.UserRepository;
import org.liuym.flowerv1springboot.service.CartService;
import org.liuym.flowerv1springboot.service.FavoriteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class CartServiceImpl implements CartService {

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private CartItemRepository cartItemRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    /** B04 移入收藏只借道收藏服务，不在购物车里复制一份收藏逻辑 */
    @Autowired
    private FavoriteService favoriteService;

    @Override
    public Cart getCartByUserId(UUID userId) {
        return cartRepository.findByUserIdWithItems(userId)
                .orElseGet(() -> createNewCart(userId));
    }

    private Cart createNewCart(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.notFound("用户不存在"));
        Cart cart = new Cart();
        cart.setUser(user);
        return cartRepository.save(cart);
    }

    @Override
    public Change addItem(UUID userId, UUID productId, Integer quantity) {
        int qty = quantity == null || quantity <= 0 ? 1 : quantity;
        Cart cart = getCartByUserId(userId);
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> BusinessException.notFound("商品不存在"));
        if (!Boolean.TRUE.equals(product.getIsActive())) {
            throw new BusinessException("商品「" + product.getName() + "」已下架");
        }

        Optional<CartItem> existingItem = cartItemRepository
                .findByCartIdAndProductId(cart.getId(), productId);
        int already = existingItem.map(CartItem::getQuantity).orElse(0);

        // B03：超出库存不再直接报错，而是钳到能加的最大值并把真实上限回传，用户不用反复点加号
        CheckoutPolicy.QuantityClamp clamp = CheckoutPolicy.clampQuantity(already + qty, product.getStock());
        if (clamp.maxAllowed() <= 0) {
            throw new BusinessException("商品「" + product.getName() + "」已售罄");
        }
        String notice = clamp.clamped()
                ? CheckoutPolicy.stockClampedText(product.getName(), clamp.maxAllowed())
                : null;
        if (already > 0 && clamp.quantity() == already) {
            return Change.of(cart, notice == null ? "已达本单可加上限 " + already + " 件" : notice);
        }

        if (existingItem.isPresent()) {
            CartItem item = existingItem.get();
            item.setPrice(product.getPrice().doubleValue());
            item.setQuantity(clamp.quantity());
            if (item.getAddedPrice() == null) {
                item.setAddedPrice(product.getPrice());
            }
            cartItemRepository.save(item);
        } else {
            CartItem newItem = new CartItem();
            newItem.setProduct(product);
            // price 必须先于 quantity：setQuantity 会触发 subtotal 计算，此时 price 不能为空
            newItem.setPrice(product.getPrice().doubleValue());
            newItem.setQuantity(clamp.quantity());
            // 加购价是降价提示（B07）的基准，只有建行时写入，之后改数量/改价都不覆盖
            newItem.setAddedPrice(product.getPrice());
            newItem.setAddedAt(LocalDateTime.now());
            newItem.setGiftWrap(false);
            cart.addItem(newItem);
            cartItemRepository.save(newItem);
        }

        cart.calculateTotal();
        return Change.of(cartRepository.save(cart), notice);
    }

    @Override
    public Change updateItem(UUID userId, UUID cartItemId, Integer quantity, Boolean giftWrap, String note) {
        Cart cart = getCartByUserId(userId);
        CartItem item = requireOwnedItem(cart, cartItemId);
        String notice = null;

        if (quantity != null) {
            if (quantity <= 0) {
                cart.removeItem(item);
                cartItemRepository.delete(item);
                cart.calculateTotal();
                return Change.of(cartRepository.save(cart), "已移除该花礼");
            }
            Product product = item.getProduct();
            CheckoutPolicy.QuantityClamp clamp = CheckoutPolicy.clampQuantity(quantity,
                    product == null ? null : product.getStock());
            if (clamp.maxAllowed() <= 0) {
                throw new BusinessException("商品「" + name(product) + "」已售罄，请移出购物车");
            }
            if (clamp.clamped()) {
                notice = CheckoutPolicy.stockClampedText(name(product), clamp.maxAllowed());
            }
            item.setQuantity(clamp.quantity());
            if (product != null && product.getPrice() != null) {
                item.setPrice(product.getPrice().doubleValue());
            }
        }
        if (giftWrap != null) {
            item.setGiftWrap(giftWrap);
        }
        if (note != null) {
            // 传空串表示清空该行备注，不能把空串当"不动"
            item.setNote(note.isBlank() ? null : CheckoutPolicy.cleanNote(note));
        }
        item.calculateSubtotal();
        cartItemRepository.save(item);

        cart.calculateTotal();
        return Change.of(cartRepository.save(cart), notice);
    }

    @Override
    public Change removeItem(UUID userId, UUID cartItemId) {
        Cart cart = getCartByUserId(userId);
        CartItem item = requireOwnedItem(cart, cartItemId);
        cart.removeItem(item);
        cartItemRepository.delete(item);
        cart.calculateTotal();
        return Change.of(cartRepository.save(cart));
    }

    @Override
    public Change moveToFavorites(UUID userId, UUID cartItemId) {
        Cart cart = getCartByUserId(userId);
        CartItem item = requireOwnedItem(cart, cartItemId);
        UUID productId = item.getProduct() == null ? null : item.getProduct().getId();
        if (productId == null) {
            throw new BusinessException("该商品已失效，无法收藏");
        }
        // toggle 是「已收藏就取消」，这里只在未收藏时翻转，避免把已收藏的花礼误删
        if (!favoriteService.isFavorite(userId, productId)) {
            favoriteService.toggle(userId, productId);
        }
        cart.removeItem(item);
        cartItemRepository.delete(item);
        cart.calculateTotal();
        return Change.of(cartRepository.save(cart), "已移入收藏");
    }

    @Override
    public Change removeInvalid(UUID userId) {
        Cart cart = getCartByUserId(userId);
        List<CartItem> invalid = cart.getItems().stream()
                .filter(item -> !item.isPurchasable())
                .toList();
        if (invalid.isEmpty()) {
            return Change.of(cart, "没有需要清理的失效商品");
        }
        invalid.forEach(cart::removeItem);
        cartItemRepository.deleteAll(invalid);
        cart.calculateTotal();
        return Change.of(cartRepository.save(cart), "已清空 " + invalid.size() + " 件失效商品");
    }

    /**
     * 归属校验必须用 equals：UUID 从不同查询/反序列化而来时是两个对象，引用比较会恒判不等或恒放行
     */
    private CartItem requireOwnedItem(Cart cart, UUID cartItemId) {
        return cart.getItems().stream()
                .filter(i -> i.getId() != null && i.getId().equals(cartItemId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(403, "无权操作该购物车项"));
    }

    private static String name(Product product) {
        return product == null || product.getName() == null ? "该商品" : product.getName();
    }

    @Override
    public Change clearCart(UUID userId) {
        Cart cart = getCartByUserId(userId);
        cartItemRepository.deleteByCartId(cart.getId());
        cart.clearItems();
        return Change.of(cartRepository.save(cart));
    }

    @Override
    public int getCartItemCount(UUID userId) {
        return cartRepository.findByUserIdWithItems(userId)
                .map(Cart::getItemCount)
                .orElse(0);
    }
}
