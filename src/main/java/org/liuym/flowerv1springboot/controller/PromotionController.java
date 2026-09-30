package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.CampaignPolicy;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.MarketingDtos;
import org.liuym.flowerv1springboot.model.Coupon;
import org.liuym.flowerv1springboot.model.FullReduction;
import org.liuym.flowerv1springboot.model.PromotionSlot;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.service.CouponService;
import org.liuym.flowerv1springboot.service.PromotionService;
import org.liuym.flowerv1springboot.vo.CouponViews.CouponView;
import org.liuym.flowerv1springboot.vo.PromotionViews;
import org.liuym.flowerv1springboot.vo.PromotionViews.ReductionView;
import org.liuym.flowerv1springboot.vo.PromotionViews.SlotView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 促销位（E19）与满减活动（E14）的后台维护 + 前台取数。
 *
 * <p>前台只按 position 拉「开关打开、在投放窗口内、按 sortOrder」的位，
 * 开关与排序全部由后台控制，页面不再硬编码营销位。
 */
@RestController
@Tag(name = "前台/后台 · 促销位与满减")
public class PromotionController {

    @Autowired
    private PromotionService promotionService;

    @Autowired
    private CouponService couponService;

    /* ---------- 前台 ---------- */

    /** 促销位：home（首页领券条）/ pdp（详情页领券条）/ center（领券中心顶部）/ cart */
    @GetMapping("/api/promotions/slots")
    public Result<List<SlotView>> slots(@RequestParam(defaultValue = "home") String position) {
        List<PromotionSlot> visible = promotionService.findVisibleSlots(position);
        List<UUID> couponIds = visible.stream().map(PromotionSlot::getCouponId).filter(java.util.Objects::nonNull).toList();
        Map<UUID, Coupon> coupons = couponIds.isEmpty() ? Map.of()
                : couponService.findByIds(couponIds).stream()
                .collect(java.util.stream.Collectors.toMap(Coupon::getId, c -> c, (a, b) -> a));
        Map<UUID, String> scopeTexts = couponService.scopeTexts(List.copyOf(coupons.values()));
        List<SlotView> views = visible.stream()
                .map(slot -> {
                    Coupon coupon = slot.getCouponId() == null ? null : coupons.get(slot.getCouponId());
                    CouponView couponView = coupon == null ? null
                            : CouponView.from(coupon, scopeTexts.get(coupon.getId()), null);
                    return SlotView.of(slot, couponView);
                })
                .toList();
        return Result.ok(views);
    }

    /** 生效中的满减活动（E14）：不传金额只列条款，传了 base 就顺带给出命中档位 */
    @GetMapping("/api/promotions/reductions")
    public Result<List<ReductionView>> reductions(@RequestParam(required = false) java.math.BigDecimal base) {
        List<FullReduction> actives = promotionService.findActiveReductions();
        java.math.BigDecimal amount = base == null ? java.math.BigDecimal.ZERO : base;
        return Result.ok(actives.stream()
                .map(reduction -> ReductionView.of(reduction, amount))
                .toList());
    }

    /* ---------- 后台：促销位 ---------- */

    @GetMapping("/api/admin/promotions/slots")
    public Result<List<SlotView>> adminSlots() {
        List<PromotionSlot> slots = promotionService.findAllSlots();
        List<UUID> couponIds = slots.stream().map(PromotionSlot::getCouponId)
                .filter(java.util.Objects::nonNull).toList();
        Map<UUID, Coupon> coupons = couponIds.isEmpty() ? Map.of()
                : couponService.findByIds(couponIds).stream()
                .collect(java.util.stream.Collectors.toMap(Coupon::getId, c -> c, (a, b) -> a));
        Map<UUID, String> scopeTexts = couponService.scopeTexts(List.copyOf(coupons.values()));
        return Result.page(slots.stream()
                .map(slot -> {
                    Coupon coupon = slot.getCouponId() == null ? null : coupons.get(slot.getCouponId());
                    return SlotView.of(slot, coupon == null ? null
                            : CouponView.from(coupon, scopeTexts.get(coupon.getId()), null));
                })
                .toList(), slots.size());
    }

    @PostMapping("/api/admin/promotions/slots")
    public Result<SlotView> createSlot(@Valid @RequestBody MarketingDtos.SlotForm form) {
        PromotionSlot slot = promotionService.saveSlot(null, form);
        return Result.ok("新增成功", SlotView.of(slot, null));
    }

    @PutMapping("/api/admin/promotions/slots/{id}")
    public Result<SlotView> updateSlot(@PathVariable UUID id, @Valid @RequestBody MarketingDtos.SlotForm form) {
        return Result.ok("更新成功", SlotView.of(promotionService.saveSlot(id, form), null));
    }

    /** E19 开关：条件更新，重复点击不会互相覆盖 */
    @PostMapping("/api/admin/promotions/slots/{id}/status")
    public Result<Void> updateSlotStatus(@PathVariable UUID id, @RequestParam String status) {
        promotionService.updateSlotStatus(id, status);
        return Result.ok("开关已保存", null);
    }

    /** E19 排序：ids 为页面上从上到下的新顺序 */
    @PostMapping("/api/admin/promotions/slots/sort")
    public Result<Void> sortSlots(@Valid @RequestBody MarketingDtos.SlotSortRequest request) {
        promotionService.resortSlots(request.ids());
        return Result.ok("排序已保存", null);
    }

    @DeleteMapping("/api/admin/promotions/slots/{id}")
    public Result<Void> deleteSlot(@PathVariable UUID id) {
        return promotionService.removeSlot(id) ? Result.ok("删除成功", null) : Result.notFound("促销位不存在");
    }

    /* ---------- 后台：满减活动 ---------- */

    @GetMapping("/api/admin/promotions/reductions")
    public Result<List<ReductionView>> adminReductions() {
        List<FullReduction> list = promotionService.findAllReductions();
        return Result.page(list.stream().map(r -> ReductionView.of(r, java.math.BigDecimal.ZERO)).toList(), list.size());
    }

    @PostMapping("/api/admin/promotions/reductions")
    public Result<ReductionView> createReduction(@Valid @RequestBody MarketingDtos.ReductionForm form) {
        FullReduction saved = promotionService.saveReduction(null, form);
        return Result.ok("创建成功", ReductionView.of(saved, java.math.BigDecimal.ZERO));
    }

    @PutMapping("/api/admin/promotions/reductions/{id}")
    public Result<ReductionView> updateReduction(@PathVariable UUID id,
                                                 @Valid @RequestBody MarketingDtos.ReductionForm form) {
        return Result.ok("更新成功", ReductionView.of(promotionService.saveReduction(id, form), java.math.BigDecimal.ZERO));
    }

    @PostMapping("/api/admin/promotions/reductions/{id}/status")
    public Result<Void> updateReductionStatus(@PathVariable UUID id, @RequestParam String status) {
        return promotionService.updateReductionStatus(id, status)
                ? Result.ok("状态更新成功", null) : Result.error("状态更新失败");
    }

    @DeleteMapping("/api/admin/promotions/reductions/{id}")
    public Result<Void> deleteReduction(@PathVariable UUID id) {
        return promotionService.removeReduction(id) ? Result.ok("删除成功", null) : Result.notFound("活动不存在");
    }

    /* ---------- E17/E18 会员权益 ---------- */

    /** 会员状态、折扣与权益清单：权益页与账户页共用一份口径 */
    @GetMapping("/api/promotions/member")
    public Result<PromotionViews.MemberView> member(HttpSession session) {
        User loginUser = CurrentUser.require(session);
        return Result.ok(PromotionViews.MemberView.of(promotionService.memberState(loginUser.getId())));
    }

    /**
     * E18：积分开通/续费。年限在服务端限死 1-3 年，积分扣减与到期延长都是条件更新，
     * 并发双击只有一份生效，失败原因（积分不足/状态已变）由业务异常直接给到页面
     */
    @PostMapping("/api/promotions/member/open")
    public Result<PromotionViews.MemberView> openMembership(@Valid @RequestBody MarketingDtos.MembershipRequest request,
                                                            HttpSession session) {
        User loginUser = CurrentUser.require(session);
        promotionService.openMembership(loginUser.getId(), request.years());
        return Result.ok(request.years() != null && request.years() > 1 ? "已续费 " + request.years() + " 年" : "会员已开通",
                PromotionViews.MemberView.of(promotionService.memberState(loginUser.getId())));
    }
}
