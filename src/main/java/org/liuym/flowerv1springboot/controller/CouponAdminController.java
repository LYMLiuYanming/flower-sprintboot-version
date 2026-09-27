package org.liuym.flowerv1springboot.controller;

import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.CouponDtos;
import org.liuym.flowerv1springboot.model.Coupon;
import org.liuym.flowerv1springboot.service.CouponService;
import org.liuym.flowerv1springboot.vo.CouponViews.CouponView;
import org.liuym.flowerv1springboot.vo.CouponViews.UserCouponView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping
    public Result<List<CouponView>> list() {
        List<Coupon> list = couponService.findAllForAdmin();
        return Result.page(CouponView.from(list), list.size());
    }

    @GetMapping("/{id}")
    public Result<CouponView> detail(@PathVariable UUID id) {
        return Result.ok(CouponView.from(couponService.findById(id)));
    }

    @PostMapping
    public Result<CouponView> create(@Valid @RequestBody CouponDtos.Form form) {
        return Result.ok("创建成功", CouponView.from(couponService.createByForm(form)));
    }

    @PutMapping("/{id}")
    public Result<CouponView> update(@PathVariable UUID id, @Valid @RequestBody CouponDtos.Form form) {
        return Result.ok("更新成功", CouponView.from(couponService.updateByForm(id, form)));
    }

    @PostMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable UUID id, @RequestParam String status) {
        return couponService.updateStatus(id, status)
                ? Result.ok("状态更新成功", null)
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
}
