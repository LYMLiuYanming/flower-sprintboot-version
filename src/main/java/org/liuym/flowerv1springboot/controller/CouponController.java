package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.CouponPolicy;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.CouponDtos;
import org.liuym.flowerv1springboot.model.Coupon;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.model.UserCoupon;
import org.liuym.flowerv1springboot.service.CouponService;
import org.liuym.flowerv1springboot.service.ProductService;
import org.liuym.flowerv1springboot.vo.CouponViews.CouponView;
import org.liuym.flowerv1springboot.vo.CouponViews.UserCouponView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 用户侧优惠券：领券、券包、结算页试算。金额一律服务端算，前端只展示
 */
@RestController
@RequestMapping("/api/coupons")
@Tag(name = "前台 · 优惠券")
public class CouponController {

    @Autowired
    private CouponService couponService;

    @Autowired
    private ProductService productService;

    /** 可领取的券：未登录也可浏览，登录后自动排除已达限领的券 */
    @GetMapping("/receivable")
    public Result<List<CouponView>> receivable(HttpSession session) {
        User loginUser = CurrentUser.of(session);
        List<Coupon> list = couponService.findReceivable(loginUser == null ? null : loginUser.getId());
        return Result.ok(CouponView.from(list));
    }

    @PostMapping("/claim")
    public Result<UserCouponView> claim(@Valid @RequestBody CouponDtos.ClaimRequest request, HttpSession session) {
        User loginUser = CurrentUser.require(session);
        UserCoupon held = couponService.claim(loginUser.getId(), request.couponId());
        return Result.ok("领取成功", UserCouponView.from(held, null));
    }

    /** 我的券包：unused / used / expired 由前端按 status 分组展示 */
    @GetMapping("/mine")
    public Result<List<UserCouponView>> mine(HttpSession session) {
        User loginUser = CurrentUser.require(session);
        List<UserCoupon> list = couponService.findMine(loginUser.getId());
        return Result.ok(list.stream().map(u -> UserCouponView.from(u, null)).toList());
    }

    /**
     * 结算页试算：按明细算出各券适用基数与可抵金额，可抵的排在前面
     */
    @PostMapping("/quote")
    public Result<Map<String, Object>> quote(@Valid @RequestBody CouponDtos.QuoteRequest request, HttpSession session) {
        User loginUser = CurrentUser.require(session);
        List<CouponPolicy.Line> lines = linesOf(request.items());
        List<UserCoupon> usable = couponService.findUsable(loginUser.getId());
        List<UserCouponView> views = usable.stream()
                .map(u -> UserCouponView.from(u, couponService.discountOf(u, lines)))
                .sorted(Comparator.comparing((UserCouponView v) -> v.discount() == null ? BigDecimal.ZERO : v.discount())
                        .reversed())
                .toList();
        BigDecimal base = lines.stream().map(CouponPolicy.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return Result.ok(Map.of("base", base.setScale(2, java.math.RoundingMode.HALF_UP), "coupons", views));
    }

    private List<CouponPolicy.Line> linesOf(List<org.liuym.flowerv1springboot.dto.OrderDtos.ItemRequest> items) {
        return items.stream().map(item -> {
            Product product = productService.findById(item.productId())
                    .orElseThrow(() -> new BusinessException("商品不存在或已下架"));
            BigDecimal amount = product.getPrice()
                    .multiply(BigDecimal.valueOf(item.quantity()))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            UUID categoryId = product.getCategory() == null ? null : product.getCategory().getId();
            return new CouponPolicy.Line(categoryId, amount);
        }).toList();
    }
}
