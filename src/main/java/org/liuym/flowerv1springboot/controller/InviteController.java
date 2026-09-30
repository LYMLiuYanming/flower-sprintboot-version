package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.MarketingDtos;
import org.liuym.flowerv1springboot.model.Coupon;
import org.liuym.flowerv1springboot.model.InviteCode;
import org.liuym.flowerv1springboot.model.InviteRelation;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.repository.CouponRepository;
import org.liuym.flowerv1springboot.service.InviteService;
import org.liuym.flowerv1springboot.vo.PromotionViews.InviteeRow;
import org.liuym.flowerv1springboot.vo.PromotionViews.InviteView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 邀请有礼（E16）：我的邀请码、绑定、首单奖励统计。
 *
 * <p>首单奖励的实际触发点在下单链路，公开方法是
 * {@link InviteService#grantFirstOrderReward(java.util.UUID, java.util.UUID, java.math.BigDecimal)}，
 * 由 OrderServiceImpl 在支付成功后调用。
 */
@RestController
@RequestMapping("/api/invite")
@Tag(name = "前台 · 邀请有礼")
public class InviteController {

    @Autowired
    private InviteService inviteService;

    @Autowired
    private CouponRepository couponRepository;

    /** 我的邀请页数据：码、专属链接、统计与被邀请记录 */
    @GetMapping("/my")
    public Result<InviteView> my(HttpSession session) {
        User loginUser = CurrentUser.require(session);
        InviteCode code = inviteService.myCode(loginUser.getId());
        List<InviteRelation> relations = inviteService.invitedBy(loginUser.getId());
        String couponName = code.getInviteeCouponId() == null ? null
                : couponRepository.findById(code.getInviteeCouponId()).map(Coupon::getName).orElse(null);
        List<InviteeRow> rows = relations.stream()
                .map(r -> new InviteeRow(r.getInviteeId(), inviteService.displayName(r.getInviteeId()),
                        r.getRewardStatus(), statusText(r.getRewardStatus()),
                        r.getCreatedAt(), r.getRewardedAt()))
                        .toList();
        return Result.ok(new InviteView(code.getCode(), "/auth/register?invite=" + code.getCode(),
                Math.toIntExact(inviteService.countInvited(loginUser.getId())),
                Math.toIntExact(inviteService.countRewarded(loginUser.getId())),
                relations.size(), inviteService.countRewarded(loginUser.getId()),
                couponName, code.getRewardPoints(), rows,
                couponName == null ? "奖励券未配置，绑定后只记录邀请关系"
                        : "TA 首单支付成功后，你和 TA 各得一张「" + couponName + "」"));
    }

    /** 注册页校验邀请码是否有效（公开接口，不暴露邀请人身份） */
    @GetMapping("/check")
    public Result<Map<String, Object>> check(@RequestParam String code) {
        Optional<InviteCode> found = inviteService.findByCode(code);
        if (found.isEmpty()) {
            return Result.ok(Map.of("valid", false, "message", "邀请码不存在或已失效"));
        }
        return Result.ok(Map.of("valid", true, "message",
                "来自 " + inviteService.displayName(found.get().getUserId()) + " 的邀请"));
    }

    /** 登录后绑定：注册页拿到邀请码后调用，一个账号只能绑一次 */
    @PostMapping("/bind")
    public Result<Map<String, Object>> bind(@Valid @RequestBody MarketingDtos.BindRequest request,
                                            HttpSession session) {
        User loginUser = CurrentUser.require(session);
        String message = inviteService.bind(request.code(), loginUser.getId());
        return Result.ok(message, Map.of("message", message));
    }

    private String statusText(String status) {
        return switch (status) {
            case InviteRelation.STATUS_REWARDED -> "首单已奖励";
            case InviteRelation.STATUS_SKIPPED -> "不满足奖励条件";
            default -> "等待首单";
        };
    }
}
