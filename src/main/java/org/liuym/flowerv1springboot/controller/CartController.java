package org.liuym.flowerv1springboot.controller;

import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.model.Cart;
import org.liuym.flowerv1springboot.service.CartService;
import org.liuym.flowerv1springboot.service.CheckoutService;
import org.liuym.flowerv1springboot.vo.CartView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 前台购物车 API：数量钳制、包装、行备注、移入收藏、失效清理与再次购买。
 * 金额一律由服务端算完再下发，前端只渲染（B01-B07、B24）。
 */
@RestController
@RequestMapping("/api/cart")
@Tag(name = "前台 · 购物车")
public class CartController {

    @Autowired
    private CartService cartService;

    @Autowired
    private CheckoutService checkoutService;

    @GetMapping
    public Result<CartView> getCart(HttpSession session) {
        return toView(cartService.getCartByUserId(CurrentUser.require(session).getId()));
    }

    @PostMapping("/add")
    @Operation(summary = "加入购物车", description = "数量按库存钳制，超出时回传真实可加数量（B03）")
    public Result<CartView> addItem(
            @RequestParam UUID productId,
            @RequestParam(defaultValue = "1") Integer quantity,
            HttpSession session) {
        UUID userId = CurrentUser.require(session).getId();
        return toView(cartService.addItem(userId, productId, quantity));
    }

    @PutMapping("/item/{itemId}")
    @Operation(summary = "修改购物车行", description = "数量、礼品包装（B01）与行备注（B02）一次改完，null 表示不改")
    public Result<CartView> updateItem(
            @PathVariable UUID itemId,
            @RequestParam(required = false) Integer quantity,
            @RequestParam(required = false) Boolean giftWrap,
            @RequestParam(required = false) String note,
            HttpSession session) {
        UUID userId = CurrentUser.require(session).getId();
        return toView(cartService.updateItem(userId, itemId, quantity, giftWrap, note));
    }

    @DeleteMapping("/item/{itemId}")
    public Result<CartView> removeItem(@PathVariable UUID itemId, HttpSession session) {
        UUID userId = CurrentUser.require(session).getId();
        return toView(cartService.removeItem(userId, itemId));
    }

    @PostMapping("/item/{itemId}/favorite")
    @Operation(summary = "移入收藏", description = "收藏该行的商品并删除该行（B04）")
    public Result<CartView> moveToFavorites(@PathVariable UUID itemId, HttpSession session) {
        UUID userId = CurrentUser.require(session).getId();
        return toView(cartService.moveToFavorites(userId, itemId));
    }

    @DeleteMapping("/invalid")
    @Operation(summary = "清空失效商品", description = "删除下架与缺货行（B06），可购行不动")
    public Result<CartView> removeInvalid(HttpSession session) {
        UUID userId = CurrentUser.require(session).getId();
        return toView(cartService.removeInvalid(userId));
    }

    @DeleteMapping("/clear")
    public Result<Void> clearCart(HttpSession session) {
        cartService.clearCart(CurrentUser.require(session).getId());
        return Result.ok("购物车已清空", null);
    }

    @PostMapping("/reorder/{orderId}")
    @Operation(summary = "再次购买", description = "把历史订单的花礼一次性加回购物车，库存不足按上限钳制（B24）")
    public Result<CartView> reorder(@PathVariable UUID orderId, HttpSession session) {
        UUID userId = CurrentUser.require(session).getId();
        var report = checkoutService.reorder(userId, orderId);
        return toView(report.cart(), report.notice())
                .with("adjusted", report.adjusted())
                .with("skipped", report.skipped());
    }

    @GetMapping("/count")
    public Result<Integer> getCartCount(HttpSession session) {
        var loginUser = CurrentUser.of(session);
        int count = loginUser == null ? 0 : cartService.getCartItemCount(loginUser.getId());
        return Result.ok(count);
    }

    /**
     * site.js 的角标读取顶层 itemCount，故随 data 一并回传；
     * notice 是服务端钳制文案（B03/B04/B06），前端直接展示不再自己编句子
     */
    private Result<CartView> toView(Cart cart) {
        return toView(CartView.from(cart), null);
    }

    private Result<CartView> toView(CartService.Change change) {
        return toView(CartView.from(change.cart()), change.notice());
    }

    private Result<CartView> toView(CartView view, String notice) {
        return Result.ok(view)
                .with("itemCount", view.itemCount())
                .with("notice", notice);
    }
}
