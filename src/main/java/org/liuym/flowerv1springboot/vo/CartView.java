package org.liuym.flowerv1springboot.vo;

import org.liuym.flowerv1springboot.model.Cart;
import org.liuym.flowerv1springboot.model.CartItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * 购物车视图：items[].product 与原实体 JSON 同构，另补充 stock/productActive 供前端做库存提示
 */
public record CartView(
        UUID id,
        Integer itemCount,
        BigDecimal totalAmount,
        List<Item> items) {

    public record Item(
            UUID id,
            ProductView product,
            Integer quantity,
            Double price,
            Double subtotal,
            Integer stock,
            Boolean productActive) {
    }

    public static CartView from(Cart cart) {
        List<Item> items = cart.getItems() == null
                ? List.of()
                : cart.getItems().stream().map(CartView::toItem).toList();
        double total = items.stream().mapToDouble(i -> i.subtotal() == null ? 0 : i.subtotal()).sum();
        int count = items.stream().mapToInt(i -> i.quantity() == null ? 0 : i.quantity()).sum();
        return new CartView(cart.getId(), count, BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP), items);
    }

    private static Item toItem(CartItem ci) {
        return new Item(
                ci.getId(),
                ci.getProduct() == null ? null : ProductView.from(ci.getProduct()),
                ci.getQuantity(),
                ci.getPrice(),
                ci.getSubtotal(),
                ci.getProduct() == null ? 0 : ci.getProduct().getStock(),
                ci.getProduct() != null && Boolean.TRUE.equals(ci.getProduct().getIsActive()));
    }
}
