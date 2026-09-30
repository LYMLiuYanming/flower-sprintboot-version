package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.ErrorCode;
import org.liuym.flowerv1springboot.common.ReplayGuard;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.model.User;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 一次性防重放凭证的签发端点（L03）。
 *
 * <p>本批只做「能力 + 出口」，不改支付/退款/领券的业务代码：
 * 前端在打开支付、申请退款、领券表单时先调这里拿 token，提交时把 token 一起带上，
 * 业务侧用 {@link ReplayGuard#acquire} 判定是否首次。接线点清单见施工报告（都在他人文件里）。
 *
 * <p>路径挂在 /api/user/** 下，已在统一鉴权拦截器的覆盖范围内，这里再 require 一次做纵深防御。
 */
@RestController
@Tag(name = "前台 · 防重放凭证")
public class ReplayTokenController {

    private final ReplayGuard replayGuard;

    public ReplayTokenController(ReplayGuard replayGuard) {
        this.replayGuard = replayGuard;
    }

    /**
     * 签发一枚一次性凭证。
     *
     * @param scope 业务动作：payment / refund / coupon-claim / coupon-transfer / point-exchange / order-create
     */
    @PostMapping("/api/user/replay-token")
    public Result<Map<String, Object>> issue(@RequestParam String scope, HttpSession session) {
        User user = CurrentUser.require(session);
        ReplayGuard.Scope parsed = scopeOf(scope);
        String token = replayGuard.issue(parsed, user.getId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("scope", parsed.code());
        data.put("ttlMinutes", replayGuard.ttlMinutes());
        return Result.ok(data);
    }

    /** 连字符写法（coupon-claim）是前端更好传的形态，枚举名是下划线，这里做一次归一 */
    private static ReplayGuard.Scope scopeOf(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("缺少凭证类型");
        }
        String key = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return ReplayGuard.Scope.valueOf(key);
        } catch (IllegalArgumentException e) {
            throw ErrorCode.BAD_REQUEST.ex("凭证类型不支持：" + raw.trim());
        }
    }
}
