package org.liuym.flowerv1springboot.controller;

import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.model.Cart;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.service.CartService;
import org.liuym.flowerv1springboot.vo.CartView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpSession;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/cart")
@Tag(name = "前台 · 购物车")
public class CartController {

    @Autowired
    private CartService cartService;

    @GetMapping
    public Result<CartView> getCart(HttpSession session) {
        User loginUser = CurrentUser.require(session);
        Cart cart = cartService.getCartByUserId(loginUser.getId());
        return toView(cart);
    }

    @PostMapping("/add")
    public Result<CartView> addItem(
            @RequestParam UUID productId,
            @RequestParam(defaultValue = "1") Integer quantity,
            HttpSession session) {
        User loginUser = CurrentUser.require(session);
        return toView(cartService.addItem(loginUser.getId(), productId, quantity));
    }

    @PutMapping("/item/{itemId}")
    public Result<CartView> updateItem(
            @PathVariable UUID itemId,
            @RequestParam Integer quantity,
            HttpSession session) {
        User loginUser = CurrentUser.require(session);
        return toView(cartService.updateItemQuantity(loginUser.getId(), itemId, quantity));
    }

    @DeleteMapping("/item/{itemId}")
    public Result<CartView> removeItem(
            @PathVariable UUID itemId,
            HttpSession session) {
        User loginUser = CurrentUser.require(session);
        return toView(cartService.removeItem(loginUser.getId(), itemId));
    }

    @DeleteMapping("/clear")
    public Result<Void> clearCart(HttpSession session) {
        User loginUser = CurrentUser.require(session);
        cartService.clearCart(loginUser.getId());
        return Result.ok("购物车已清空", null);
    }

    @GetMapping("/count")
    public Result<Integer> getCartCount(HttpSession session) {
        User loginUser = CurrentUser.of(session);
        int count = loginUser == null ? 0 : cartService.getCartItemCount(loginUser.getId());
        return Result.ok(count);
    }

    /**
     * site.js 的角标读取顶层 itemCount，故随 data 一并回传
     */
    private Result<CartView> toView(Cart cart) {
        CartView view = CartView.from(cart);
        return Result.ok(view).with("itemCount", view.itemCount());
    }
}
