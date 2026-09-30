package org.liuym.flowerv1springboot.vo;

import org.liuym.flowerv1springboot.common.CheckoutPolicy;
import org.liuym.flowerv1springboot.model.Cart;
import org.liuym.flowerv1springboot.model.CartItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * 购物车视图：items 只含可购行，失效行单列 invalidItems 且不参与合计（B05）。
 * 价格与库存一律取商品当前值，行上保存的 price 只是加购时的展示快照。
 */
public record CartView(
        UUID id,
        Integer itemCount,
        BigDecimal totalAmount,
        List<Item> items,
        /** 下架 / 缺货行（B05）：页面单独分组，可一键清掉（B06） */
        List<Item> invalidItems,
        Integer invalidCount,
        /** 失效行原价合计：清空前提示用户这些花礼原值多少 */
        BigDecimal invalidAmount,
        /** 礼品包装（B01）：勾选束数与整单加价额 */
        Integer giftWrapKinds,
        BigDecimal giftWrapFee,
        /** 比加入时降价合计（B07） */
        BigDecimal priceDropAmount,
        /** 单行数量上限（B03），前端 max 属性与后端钳制同源 */
        Integer maxQuantityPerItem) {

    public record Item(
            UUID id,
            ProductView product,
            Integer quantity,
            Double price,
            Double subtotal,
            Integer stock,
            Boolean productActive,
            /** 本行可加到的最大数量（B03）：min(库存, 单行上限) */
            Integer maxQuantity,
            Boolean giftWrap,
            String note,
            /** 加购价（B07）与相对加购时的降价额 */
            BigDecimal addedPrice,
            BigDecimal priceDrop,
            String invalidReason) {
    }

    public static CartView from(Cart cart) {
        List<CartItem> all = cart.getItems() == null ? List.of() : cart.getItems();
        List<Item> valid = all.stream().filter(CartItem::isPurchasable).map(CartView::toItem).toList();
        List<Item> invalid = all.stream().filter(i -> !i.isPurchasable()).map(CartView::toItem).toList();
        double total = valid.stream().mapToDouble(i -> i.subtotal() == null ? 0 : i.subtotal()).sum();
        int count = valid.stream().mapToInt(i -> i.quantity() == null ? 0 : i.quantity()).sum();
        double invalidTotal = invalid.stream().mapToDouble(i -> i.subtotal() == null ? 0 : i.subtotal()).sum();
        int wrapped = (int) all.stream().filter(CartItem::isGiftWrapped).filter(CartItem::isPurchasable).count();
        BigDecimal drop = valid.stream()
                .map(i -> i.priceDrop() == null ? BigDecimal.ZERO : i.priceDrop())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        return new CartView(
                cart.getId(),
                count,
                BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP),
                valid,
                invalid,
                invalid.size(),
                BigDecimal.valueOf(invalidTotal).setScale(2, RoundingMode.HALF_UP),
                wrapped,
                CheckoutPolicy.giftWrapFee(wrapped),
                drop,
                CheckoutPolicy.MAX_QUANTITY_PER_ITEM);
    }

    private static Item toItem(CartItem ci) {
        ProductView product = ci.getProduct() == null ? null : ProductView.from(ci.getProduct());
        Integer stock = product == null || product.stock() == null ? 0 : product.stock();
        boolean active = product != null && Boolean.TRUE.equals(product.isActive());
        String reason = !active ? "已下架" : (stock <= 0 ? "暂时缺货" : null);
        int maxQuantity = Math.min(Math.max(stock, 0), CheckoutPolicy.MAX_QUANTITY_PER_ITEM);
        return new Item(
                ci.getId(),
                product,
                ci.getQuantity(),
                ci.getPrice(),
                ci.getSubtotal(),
                stock,
                active,
                maxQuantity,
                ci.isGiftWrapped(),
                ci.getNote(),
                ci.getAddedPrice(),
                ci.priceDrop(),
                reason);
    }
}
