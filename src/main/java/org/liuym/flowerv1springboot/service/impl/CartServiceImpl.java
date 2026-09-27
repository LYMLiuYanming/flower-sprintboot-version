package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.model.Cart;
import org.liuym.flowerv1springboot.model.CartItem;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.repository.CartItemRepository;
import org.liuym.flowerv1springboot.repository.CartRepository;
import org.liuym.flowerv1springboot.repository.ProductRepository;
import org.liuym.flowerv1springboot.repository.UserRepository;
import org.liuym.flowerv1springboot.service.CartService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class CartServiceImpl implements CartService {

    /** 单个商品在购物车中的数量上限，避免恶意刷高行项目 */
    private static final int MAX_QUANTITY_PER_ITEM = 99;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private CartItemRepository cartItemRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

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
    public Cart addItem(UUID userId, UUID productId, Integer quantity) {
        int qty = quantity == null || quantity <= 0 ? 1 : quantity;
        Cart cart = getCartByUserId(userId);
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> BusinessException.notFound("商品不存在"));
        if (!Boolean.TRUE.equals(product.getIsActive())) {
            throw new BusinessException("商品「" + product.getName() + "」已下架");
        }

        Optional<CartItem> existingItem = cartItemRepository
                .findByCartIdAndProductId(cart.getId(), productId);

        int target = existingItem.map(CartItem::getQuantity).orElse(0) + qty;
        assertStockEnough(product, target);

        if (existingItem.isPresent()) {
            CartItem item = existingItem.get();
            item.setPrice(product.getPrice().doubleValue());
            item.setQuantity(target);
            cartItemRepository.save(item);
        } else {
            CartItem newItem = new CartItem();
            newItem.setProduct(product);
            // price 必须先于 quantity：setQuantity 会触发 subtotal 计算，此时 price 不能为空
            newItem.setPrice(product.getPrice().doubleValue());
            newItem.setQuantity(target);
            cart.addItem(newItem);
            cartItemRepository.save(newItem);
        }

        cart.calculateTotal();
        return cartRepository.save(cart);
    }

    @Override
    public Cart updateItemQuantity(UUID userId, UUID cartItemId, Integer quantity) {
        Cart cart = getCartByUserId(userId);
        CartItem item = requireOwnedItem(cart, cartItemId);

        if (quantity == null || quantity <= 0) {
            cart.removeItem(item);
            cartItemRepository.delete(item);
        } else {
            Product product = item.getProduct();
            assertStockEnough(product, quantity);
            item.setQuantity(quantity);
            item.setPrice(product.getPrice().doubleValue());
            item.calculateSubtotal();
            cartItemRepository.save(item);
        }

        cart.calculateTotal();
        return cartRepository.save(cart);
    }

    @Override
    public Cart removeItem(UUID userId, UUID cartItemId) {
        Cart cart = getCartByUserId(userId);
        CartItem item = requireOwnedItem(cart, cartItemId);
        cart.removeItem(item);
        cartItemRepository.delete(item);
        cart.calculateTotal();
        return cartRepository.save(cart);
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

    private void assertStockEnough(Product product, int quantity) {
        if (quantity > MAX_QUANTITY_PER_ITEM) {
            throw new BusinessException("单个商品最多购买 " + MAX_QUANTITY_PER_ITEM + " 件");
        }
        Integer stock = product.getStock();
        if (stock == null || stock < quantity) {
            throw new BusinessException("商品「" + product.getName() + "」库存不足");
        }
    }

    @Override
    public Cart clearCart(UUID userId) {
        Cart cart = getCartByUserId(userId);
        cartItemRepository.deleteByCartId(cart.getId());
        cart.clearItems();
        return cartRepository.save(cart);
    }

    @Override
    public int getCartItemCount(UUID userId) {
        return cartRepository.findByUserIdWithItems(userId)
                .map(Cart::getItemCount)
                .orElse(0);
    }
}
