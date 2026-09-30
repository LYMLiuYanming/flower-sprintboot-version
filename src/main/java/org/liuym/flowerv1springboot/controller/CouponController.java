package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.CampaignPolicy;
import org.liuym.flowerv1springboot.common.CouponPolicy;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.CouponDtos;
import org.liuym.flowerv1springboot.dto.OrderDtos;
import org.liuym.flowerv1springboot.model.Coupon;
import org.liuym.flowerv1springboot.model.FullReduction;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.model.UserCoupon;
import org.liuym.flowerv1springboot.service.CouponService;
import org.liuym.flowerv1springboot.service.ProductService;
import org.liuym.flowerv1springboot.service.PromotionService;
import org.liuym.flowerv1springboot.vo.CouponViews;
import org.liuym.flowerv1springboot.vo.CouponViews.ComboPlan;
import org.liuym.flowerv1springboot.vo.CouponViews.CouponView;
import org.liuym.flowerv1springboot.vo.CouponViews.UserCouponView;
import org.liuym.flowerv1springboot.vo.PromotionViews;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 用户侧优惠券：领券、券包、转赠、结算试算与优惠方案。金额一律服务端算，前端只展示
 */
@RestController
@RequestMapping("/api/coupons")
@Tag(name = "前台 · 优惠券")
public class CouponController {

    @Autowired
    private CouponService couponService;

    @Autowired
    private ProductService productService;

    @Autowired
    private PromotionService promotionService;

    /** 可领取的券：未登录也可浏览，登录后自动排除已达限领的券（首页/详情页领券条用） */
    @GetMapping("/receivable")
    public Result<List<CouponView>> receivable(HttpSession session) {
        User loginUser = CurrentUser.of(session);
        List<Coupon> list = couponService.findReceivable(loginUser == null ? null : loginUser.getId());
        return Result.ok(views(list, loginUser));
    }

    /**
     * E10 领券中心：按分类筛选，不可领的券也返回并打上原因标记（已领过 / 今日已领 / 新客专享…）
     */
    @GetMapping("/center")
    public Result<List<CouponView>> center(@RequestParam(required = false) UUID categoryId, HttpSession session) {
        User loginUser = CurrentUser.of(session);
        List<Coupon> list = couponService.findCenter(loginUser == null ? null : loginUser.getId(), categoryId);
        return Result.ok(views(list, loginUser));
    }

    @PostMapping("/claim")
    public Result<UserCouponView> claim(@Valid @RequestBody CouponDtos.ClaimRequest request, HttpSession session) {
        User loginUser = CurrentUser.require(session);
        UserCoupon held = couponService.claim(loginUser.getId(), request.couponId());
        return Result.ok("领取成功", UserCouponView.from(held, null, scopeTextOf(held), null));
    }

    /** 我的券包：unused / used / expired / gifting 由前端按 status 分组展示 */
    @GetMapping("/mine")
    public Result<List<UserCouponView>> mine(HttpSession session) {
        User loginUser = CurrentUser.require(session);
        List<UserCoupon> list = couponService.findMine(loginUser.getId());
        Map<UUID, String> scopeTexts = couponService.heldScopeTexts(list);
        return Result.ok(list.stream()
                .map(u -> UserCouponView.from(u, null, scopeTexts.get(u.getId()), null))
                .toList());
    }

    /**
     * 结算页试算：按明细算出各券适用基数与可抵金额，可抵的排在前面；
     * 抵不动的券带上明确原因（E08），不再只写一句「不可用」
     */
    @PostMapping("/quote")
    public Result<Map<String, Object>> quote(@Valid @RequestBody CouponDtos.QuoteRequest request, HttpSession session) {
        User loginUser = CurrentUser.require(session);
        List<CouponPolicy.Line> lines = linesOf(request.items());
        List<UserCoupon> usable = couponService.findUsable(loginUser.getId());
        Map<UUID, String> scopeTexts = couponService.heldScopeTexts(usable);
        List<UserCouponView> views = usable.stream()
                .map(u -> {
                    BigDecimal discount = couponService.discountOf(u, lines);
                    String reason = discount == null ? reasonOf(u, lines) : null;
                    return UserCouponView.from(u, discount, scopeTexts.get(u.getId()), reason);
                })
                .sorted(Comparator.comparing((UserCouponView v) -> v.discount() == null ? BigDecimal.ZERO : v.discount())
                        .reversed())
                        .toList();
        return Result.ok(Map.of("base", base(lines), "coupons", views));
    }

    /**
     * E08：下单前的单券诊断。返回 usable / discount / reason / retryable，
     * 结算页据此决定「换个组合再试」还是「这张券本来就不行」
     */
    @PostMapping("/check")
    public Result<Map<String, Object>> check(@Valid @RequestBody CouponDtos.CheckRequest request, HttpSession session) {
        User loginUser = CurrentUser.require(session);
        List<CouponPolicy.Line> lines = linesOf(request.items());
        UserCoupon held = couponService.findUsable(loginUser.getId()).stream()
                .filter(u -> u.getId().equals(request.userCouponId()))
                .findFirst().orElse(null);
        if (held == null) {
            String reason = couponService.consumeFailureReason(request.userCouponId(), loginUser.getId());
            return Result.ok(Map.of("usable", false, "discount", BigDecimal.ZERO.setScale(2),
                    "reason", reason, "retryable", reason.contains("刷新") || reason.contains("转赠")));
        }
        BigDecimal discount = couponService.discountOf(held, lines);
        return Result.ok(Map.of("usable", discount != null,
                "discount", discount == null ? BigDecimal.ZERO.setScale(2) : discount,
                "reason", discount == null ? reasonOf(held, lines) : "",
                "retryable", discount == null));
    }

    /**
     * E09 + E14：结算页优惠方案。满减（无需领券）、券、积分三种减免全算一遍，
     * 再按「可叠加 / 取较优」给出建议，页面只负责渲染
     */
    @PostMapping("/plan")
    public Result<Map<String, Object>> plan(@Valid @RequestBody CouponDtos.ComboRequest request, HttpSession session) {
        User loginUser = CurrentUser.require(session);
        List<CouponPolicy.Line> lines = linesOf(request.items());
        ComboPlan combo = couponService.comboPlan(loginUser.getId(), lines, request.userCouponId());
        Optional<FullReduction> hit = promotionService.bestReduction(lines);
        CampaignPolicy.Tier tier = hit.map(r -> promotionService.hitReduction(r, lines)).orElse(null);
        CampaignPolicy.Plan stacked = CampaignPolicy.combineReductionAndCoupon(
                tier, hit.map(FullReduction::getName).orElse(null),
                hit.map(FullReduction::getStackWithCoupon).orElse(false),
                combo.couponDiscount(), combo.couponName());
        return Result.ok(Map.of(
                "base", combo.base(),
                "points", combo.points(),
                "summary", combo.summary(),
                "options", combo.options(),
                "reduction", tier == null ? Map.of("hit", false) : Map.of(
                        "hit", true,
                        "name", hit.get().getName(),
                        "reduce", tier.reduce(),
                        "stackWithCoupon", Boolean.TRUE.equals(hit.get().getStackWithCoupon())),
                "comboSummary", stacked.summary(),
                "comboSaved", stacked.totalSaved()));
    }

    /**
     * E06：到期提醒条。券包页顶部与账户角标共用，文案已由 expireReminder 字段生成
     */
    @GetMapping("/expiring")
    public Result<List<UserCouponView>> expiring(@RequestParam(required = false) Integer days, HttpSession session) {
        User loginUser = CurrentUser.require(session);
        List<UserCoupon> list = couponService.expiringSoon(loginUser.getId(),
                days == null ? CouponPolicy.REMIND_BEFORE_DAYS : days);
        Map<UUID, String> scopeTexts = couponService.heldScopeTexts(list);
        return Result.ok(list.stream()
                .map(u -> UserCouponView.from(u, null, scopeTexts.get(u.getId()), null))
                .toList());
    }

    /**
     * E14：满减（无需领券）在本单上的预览。列出每条生效活动的档位与命中情况，
     * 再给出「本单最优的一条」和能不能与券叠加，购物车与结算页共用同一份口径
     */
    @PostMapping("/reduction-preview")
    public Result<PromotionViews.ReductionPreview> reductionPreview(
            @Valid @RequestBody CouponDtos.QuoteRequest request, HttpSession session) {
        CurrentUser.require(session);
        List<CouponPolicy.Line> lines = linesOf(request.items());
        List<PromotionViews.ReductionView> views = promotionService.findActiveReductions().stream()
                .map(reduction -> PromotionViews.ReductionView.of(reduction,
                        promotionService.reductionBase(reduction, lines)))
                .toList();
        Optional<FullReduction> best = promotionService.bestReduction(lines);
        CampaignPolicy.Tier tier = best.map(reduction -> promotionService.hitReduction(reduction, lines)).orElse(null);
        boolean stackable = best.map(reduction -> Boolean.TRUE.equals(reduction.getStackWithCoupon())).orElse(false);
        return Result.ok(new PromotionViews.ReductionPreview(base(lines), views,
                best.map(FullReduction::getName).orElse(null),
                tier == null ? null : tier.reduce(), stackable,
                tier == null ? "本单还未达到任何满减门槛"
                        : "已自动享「" + best.get().getName() + "」减 " + tier.reduce().toPlainString()
                                + (stackable ? "，可与优惠券叠加" : "，与优惠券取更省的一条")));
    }

    /* ---------- E07 转赠 ---------- */

    @PostMapping("/transfer")
    public Result<UserCouponView> startTransfer(@Valid @RequestBody CouponDtos.TransferRequest request,
                                                HttpSession session) {
        User loginUser = CurrentUser.require(session);
        UserCoupon held = couponService.startTransfer(loginUser.getId(), request.userCouponId(), request.receiver());
        return Result.ok("转赠已生成，把转赠码发给对方即可",
                UserCouponView.from(held, null, scopeTextOf(held), null));
    }

    @PostMapping("/transfer/cancel")
    public Result<UserCouponView> cancelTransfer(@Valid @RequestBody CouponDtos.TransferCancelRequest request,
                                                 HttpSession session) {
        User loginUser = CurrentUser.require(session);
        UserCoupon held = couponService.cancelTransfer(loginUser.getId(), request.userCouponId());
        return Result.ok("已撤销转赠", UserCouponView.from(held, null, scopeTextOf(held), null));
    }

    /** 凭码领取：条件更新保证两个人同时提交只有一个人拿得到 */
    @PostMapping("/transfer/accept")
    public Result<UserCouponView> acceptTransfer(@Valid @RequestBody CouponDtos.TransferAcceptRequest request,
                                                 HttpSession session) {
        User loginUser = CurrentUser.require(session);
        UserCoupon held = couponService.acceptTransfer(loginUser.getId(), request.token());
        return Result.ok("已收到这份转赠", UserCouponView.from(held, null, scopeTextOf(held), null));
    }

    @GetMapping("/transfer/incoming")
    public Result<List<UserCouponView>> incomingTransfers(HttpSession session) {
        User loginUser = CurrentUser.require(session);
        List<UserCoupon> list = couponService.incomingTransfers(loginUser.getId());
        Map<UUID, String> scopeTexts = couponService.heldScopeTexts(list);
        return Result.ok(list.stream()
                .map(u -> UserCouponView.from(u, null, scopeTexts.get(u.getId()), null))
                .toList());
    }

    /* ---------- 内部 ---------- */

    private List<CouponView> views(List<Coupon> coupons, User loginUser) {
        Map<UUID, String> scopeTexts = couponService.scopeTexts(coupons);
        LocalDateTime now = LocalDateTime.now();
        UUID userId = loginUser == null ? null : loginUser.getId();
        return coupons.stream()
                .map(c -> CouponView.from(c, scopeTexts.get(c.getId()),
                        couponService.claimBlockReason(c, userId, now)))
                .toList();
    }

    private String scopeTextOf(UserCoupon held) {
        return couponService.heldScopeTexts(List.of(held)).get(held.getId());
    }

    /** E08：把「为什么抵不动」说清楚，分类券要说清是没匹配到分类还是差门槛 */
    private String reasonOf(UserCoupon held, List<CouponPolicy.Line> lines) {
        return CouponPolicy.unavailableReason(held, null, lines);
    }

    private BigDecimal base(List<CouponPolicy.Line> lines) {
        return lines.stream().map(CouponPolicy.Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    private List<CouponPolicy.Line> linesOf(List<OrderDtos.ItemRequest> items) {
        return items.stream().map(item -> {
            Product product = productService.findById(item.productId())
                    .orElseThrow(() -> new BusinessException("商品不存在或已下架"));
            BigDecimal amount = product.getPrice()
                    .multiply(BigDecimal.valueOf(item.quantity()))
                    .setScale(2, RoundingMode.HALF_UP);
            UUID categoryId = product.getCategory() == null ? null : product.getCategory().getId();
            // 带上 productId：商品白名单券（E03）只认名单内的行，缺 id 会被算成不适用
            return new CouponPolicy.Line(categoryId, amount, product.getId());
        }).toList();
    }
}
