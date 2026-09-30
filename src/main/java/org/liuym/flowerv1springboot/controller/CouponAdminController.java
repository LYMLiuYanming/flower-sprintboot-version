package org.liuym.flowerv1springboot.controller;

import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.CouponDtos;
import org.liuym.flowerv1springboot.model.Coupon;
import org.liuym.flowerv1springboot.service.CouponService;
import org.liuym.flowerv1springboot.vo.CouponViews.CouponStatView;
import org.liuym.flowerv1springboot.vo.CouponViews.CouponView;
import org.liuym.flowerv1springboot.vo.CouponViews.UserCouponView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/admin/coupons")
@Tag(name = "后台 · 优惠券管理")
public class CouponAdminController {

    @Autowired
    private CouponService couponService;

    /** 列表直接带 E13 统计：领取数 / 核销数 / 核销率 */
    @GetMapping
    public Result<List<CouponView>> list() {
        List<Coupon> list = couponService.findAllForAdmin();
        Map<UUID, String> scopeTexts = couponService.scopeTexts(list);
        Map<UUID, CouponStatView> stats = couponService.statsForAdmin().stream()
                .collect(java.util.stream.Collectors.toMap(CouponStatView::couponId, s -> s, (a, b) -> a));
        LocalDateTime now = LocalDateTime.now();
        List<CouponView> views = list.stream()
                .map(coupon -> {
                    CouponStatView stat = stats.get(coupon.getId());
                    return CouponView.withStats(coupon, scopeTexts.get(coupon.getId()),
                            stat == null ? 0 : stat.claimedCount(),
                            stat == null ? 0 : stat.usedCount(),
                            stat == null ? null : stat.useRate());
                })
                .toList();
        return Result.page(views, list.size());
    }

    @GetMapping("/{id}")
    public Result<CouponView> detail(@PathVariable UUID id) {
        Coupon coupon = couponService.findById(id);
        return Result.ok(CouponView.from(coupon, couponService.scopeTexts(List.of(coupon)).get(id), null));
    }

    @PostMapping
    public Result<CouponView> create(@Valid @RequestBody CouponDtos.Form form) {
        Coupon coupon = couponService.createByForm(form);
        return Result.ok("创建成功", CouponView.from(coupon));
    }

    @PutMapping("/{id}")
    public Result<CouponView> update(@PathVariable UUID id, @Valid @RequestBody CouponDtos.Form form) {
        Coupon coupon = couponService.updateByForm(id, form);
        return Result.ok("更新成功", CouponView.from(coupon));
    }

    /** E11：复制新建。返回的副本默认停用，改完条款再上架 */
    @PostMapping("/{id}/copy")
    public Result<CouponView> copy(@PathVariable UUID id) {
        Coupon copy = couponService.copy(id);
        return Result.ok("已复制为「" + copy.getCode() + "」，副本默认停用", CouponView.from(copy));
    }

    /**
     * 上下架。policy 为空时按模板既有策略执行；void 会作废该模板下未使用的券（E12）
     */
    @PostMapping("/{id}/status")
    public Result<Map<String, Object>> updateStatus(@PathVariable UUID id,
                                                    @RequestParam String status,
                                                    @RequestParam(required = false) String policy) {
        int voided = couponService.updateStatus(id, status, policy);
        boolean success = Coupon.STATUS_ACTIVE.equals(status) || voided >= 0;
        return success
                ? Result.ok(voided > 0 ? "状态更新成功，已作废未使用券 " + voided + " 张" : "状态更新成功",
                Map.of("voidedCount", voided))
                : Result.error("状态更新失败");
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable UUID id) {
        couponService.delete(id);
        return Result.ok("删除成功", null);
    }

    /** 后台定向发券：用户拿到的同样是条款快照 */
    @PostMapping("/issue")
    public Result<Map<String, Object>> issue(@Valid @RequestBody CouponDtos.IssueRequest request) {
        var held = couponService.issue(request.couponId(), request.userId());
        return Result.ok("已发放", Map.of("userCoupon", UserCouponView.from(held, null)));
    }

    /** E13：单独取统计，后台列表页汇总条用 */
    @GetMapping("/stats")
    public Result<List<CouponStatView>> stats() {
        return Result.ok(couponService.statsForAdmin());
    }
}
