package org.liuym.flowerv1springboot.checkout;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.model.Cart;
import org.liuym.flowerv1springboot.model.CartItem;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.vo.CartView;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 购物车视图分组与降价口径（B05/B06/B07/B01/B03）：
 * 视图层算错一次，购物车与结算页就会各显示一套金额
 */
class CartViewTest {

    private static Product product(String name, String price, int stock, boolean active) {
        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setName(name);
        product.setPrice(new BigDecimal(price));
        product.setStock(stock);
        product.setIsActive(active);
        product.setUnit("束");
        return product;
    }

    private static CartItem row(Product product, int quantity, String addedPrice, boolean giftWrap) {
        CartItem item = new CartItem();
        item.setId(UUID.randomUUID());
        item.setProduct(product);
        item.setPrice(product.getPrice().doubleValue());
        item.setQuantity(quantity);
        item.setAddedPrice(addedPrice == null ? null : new BigDecimal(addedPrice));
        item.setGiftWrap(giftWrap);
        return item;
    }

    @Test
    void invalidLinesAreGroupedAndExcludedFromTotal() {
        Cart cart = new Cart();
        cart.addItem(row(product("玫瑰恋歌", "199.00", 10, true), 2, "199.00", false));
        cart.addItem(row(product("下架绣球", "259.00", 5, false), 1, "259.00", false));
        cart.addItem(row(product("缺货郁金香", "129.00", 0, true), 1, "129.00", false));

        CartView view = CartView.from(cart);
        assertEquals(1, view.items().size());
        assertEquals(2, view.invalidItems().size());
        assertEquals(2, view.invalidCount());
        // 失效行不参与合计（B05）
        assertEquals(new BigDecimal("398.00"), view.totalAmount());
        assertEquals(2, view.itemCount());
        assertEquals(new BigDecimal("388.00"), view.invalidAmount());
        assertEquals("已下架", view.invalidItems().stream()
                .filter(i -> "下架绣球".equals(i.product().name())).findFirst().orElseThrow().invalidReason());
        assertEquals("暂时缺货", view.invalidItems().stream()
                .filter(i -> "缺货郁金香".equals(i.product().name())).findFirst().orElseThrow().invalidReason());
    }

    @Test
    void priceDropUsesAddedSnapshot() {
        Cart cart = new Cart();
        cart.addItem(row(product("向日葵", "169.00", 6, true), 1, "189.00", true));
        cart.addItem(row(product("涨价百合", "220.00", 3, true), 1, "200.00", false));

        CartView view = CartView.from(cart);
        // 降价 20 元、涨价的行不给提示（B07）
        assertEquals(new BigDecimal("20.00"), view.priceDropAmount());
        CartView.Item dropped = view.items().get(0);
        assertEquals(new BigDecimal("20.00"), dropped.priceDrop());
        assertEquals("189.00", dropped.addedPrice().toPlainString());
        assertEquals(BigDecimal.ZERO, view.items().get(1).priceDrop());
        // 勾选了包装的行让整单出现包装费（B01）
        assertEquals(1, view.giftWrapKinds());
        assertEquals(new BigDecimal("12.00"), view.giftWrapFee());
    }

    @Test
    void maxQuantityFollowsStockAndPerItemCap() {
        Cart cart = new Cart();
        cart.addItem(row(product("库存 2", "59.00", 2, true), 2, "59.00", false));
        cart.addItem(row(product("库存充足", "99.00", 500, true), 1, "99.00", false));

        CartView view = CartView.from(cart);
        assertEquals(2, view.items().get(0).maxQuantity());
        // 库存再大也受单行上限约束（B03）
        assertEquals(99, view.items().get(1).maxQuantity());
        assertEquals(99, view.maxQuantityPerItem());
    }

    @Test
    void noteIsTrimmedToSingleLine() {
        CartItem item = row(product("玫瑰", "99.00", 5, true), 1, "99.00", false);
        item.setNote("\n卡片写：妈妈辛苦了   ");
        assertEquals("卡片写：妈妈辛苦了", item.getNote());
        item.setNote(null);
        assertNull(item.getNote());
    }
}
