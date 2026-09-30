package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Pages;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.common.UserDispositionPolicy;
import org.liuym.flowerv1springboot.dto.UserDtos;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.repository.AdminQueryRepository;
import org.liuym.flowerv1springboot.service.UserService;
import org.liuym.flowerv1springboot.vo.SupportViews.UserView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 后台用户管理：输出一律走 UserView（不含 password），
 * 管理员不能禁用自己；重置密码要求管理员自填初始密码——服务端生成的口令不回显也不落日志，
 * 一旦回显就等于把它同时写进了浏览器历史与审计摘要
 */
@RestController
@RequestMapping("/api/admin/users")
@Tag(name = "后台 · 用户管理")
public class UserAdminController {

    /** 与注册口令同一条规则：8-32 位且字母数字都有，后台不该成为弱密码的后门 */
    private static final Pattern PASSWORD_RULE = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,32}$");

    private final UserService userService;
    private final AdminQueryRepository adminQueryRepository;

    public UserAdminController(UserService userService, AdminQueryRepository adminQueryRepository) {
        this.userService = userService;
        this.adminQueryRepository = adminQueryRepository;
    }

    /**
     * 列表筛选（G15）：关键词 / 身份 / 会员等级 / 账号状态 / 注册时间区间，五个条件可任意组合。
     * 等级与时间区间不在 UserService.search 的口径里，走本组的只读查询补齐。
     * 顶层附带 dispositions（G17）：userId → 最近一次禁/启用的原因与操作人，
     * 数据来自审计留痕而不是 user 表新列，列表页据此把「为什么被禁用」直接显示出来
     */
    @GetMapping
    public Result<List<UserView>> list(@RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "10") int limit,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String userType,
                                       @RequestParam(required = false) String memberLevel,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String registeredFrom,
                                       @RequestParam(required = false) String registeredTo,
                                       @RequestParam(required = false) String sort,
                                       @RequestParam(required = false) String order,
                                       HttpSession session) {
        CurrentUser.requireAdmin(session);
        LocalDateTime from = dateOf(registeredFrom, false);
        LocalDateTime to = dateOf(registeredTo, true);
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException("注册起始时间不能晚于结束时间");
        }
        Page<User> result = adminQueryRepository.searchUsers(trimToNull(keyword), trimToNull(userType),
                trimToNull(memberLevel), trimToNull(status), from, to,
                Pages.of(page, limit, userSort(sort, order)));
        return Result.page(result.getContent().stream().map(UserView::from).toList(), result.getTotalElements())
                .with("dispositions", latestDispositions(result.getContent()));
    }

    /** 详情（G18）：账号信息之外直接带上订单数、消费额、券包、积分，页面不必再发第二次请求 */
    @GetMapping("/{id}")
    public Result<UserView> detail(@PathVariable UUID id) {
        return userService.findById(id).map(user -> Result.ok(UserView.from(user))
                        .with("stats", statsOf(id))
                        .with("dispositions", Map.of(String.valueOf(id),
                                firstDispositionOf(user.getId()))))
                .orElseGet(() -> Result.notFound("用户不存在"));
    }

    @GetMapping("/{id}/stats")
    public Result<Map<String, Object>> stats(@PathVariable UUID id) {
        return Result.ok(statsOf(id));
    }

    /** 该账号最近的处置留痕（G17 的原因回查）：禁用/启用/重置密码的时间、操作人与理由 */
    @GetMapping("/{id}/dispositions")
    public Result<List<Map<String, Object>>> dispositions(@PathVariable UUID id,
                                                           @RequestParam(defaultValue = "10") int limit) {
        List<Map<String, Object>> rows = adminQueryRepository.userDispositionLogs(id, limit).stream()
                .map(row -> {
                    String detail = String.valueOf(row[5] == null ? "" : row[5]);
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("createdAt", row[0]);
                    item.put("action", row[1]);
                    item.put("operatorName", row[2]);
                    item.put("resultCode", row[3]);
                    item.put("resultMsg", row[4]);
                    // detail 里存的是请求体（口令字段已在写库时掩码），这里只回原因，不把整包参数甩给页面
                    item.put("reason", reasonOf(detail));
                    item.put("targetStatus", jsonStringField(detail, "status"));
                    return item;
                }).toList();
        return Result.ok(rows);
    }

    /**
     * 禁用 / 启用 / 锁定（G17）：停用与锁定必须写明原因，原因随请求体进入审计留痕（admin_audit_log.detail），
     * 不新增 user 表列——理由是「一次处置的属性」而不是「账号的属性」，历史处置都要能追溯
     */
    @PostMapping("/{id}/status")
    public Result<UserView> changeStatus(@PathVariable UUID id,
                                         @Valid @RequestBody UserDtos.AdminUserStatusRequest request,
                                         HttpSession session) {
        requireNotSelf(id, session, "不能修改自己的账号状态");
        UserDispositionPolicy policy = UserDispositionPolicy.of(request.status(), request.reason()).requireValid();
        User target = userService.findById(id).orElseThrow(() -> BusinessException.notFound("用户不存在"));
        if (!userService.updateStatus(id, policy.status())) {
            throw BusinessException.notFound("用户不存在");
        }
        // 响应 msg 会被 AdminAuditFilter 摘成 resultMsg，所以把「谁被处置、为什么」写进 msg，
        // 审计列表不展开详情也能看出这一条是谁以什么理由动的
        return Result.ok(policy.describe(target.getUsername()) + " 已生效",
                UserView.from(userService.findById(id).orElseThrow()));
    }

    /**
     * 管理员重置密码（G16）：初始密码由管理员自行设定并当面/线下转交，
     * 响应体里不回传口令，理由与操作人由 AdminAuditFilter 落在审计里（password 字段已掩码）
     */
    @PostMapping("/{id}/reset-password")
    public Result<Void> resetPassword(@PathVariable UUID id,
                                      @Valid @RequestBody UserDtos.ResetPasswordRequest request,
                                      HttpSession session) {
        requireNotSelf(id, session, "请使用个人中心修改自己的密码");
        String initial = request.newPassword();
        if (initial == null || initial.isBlank()) {
            throw new BusinessException("请填写要设置的初始密码，系统不会代生成分发口令");
        }
        if (!PASSWORD_RULE.matcher(initial).matches()) {
            throw new BusinessException("初始密码需为 8-32 位且同时包含字母和数字");
        }
        userService.resetPasswordByAdmin(id, initial);
        return Result.ok("密码已重置，该用户下次登录将被强制修改密码", null);
    }

    @GetMapping("/count")
    public Result<Long> count() {
        return Result.ok(userService.count());
    }

    private Map<String, Object> statsOf(UUID id) {
        Map<String, Object> stats = new LinkedHashMap<>(adminQueryRepository.userOrderStats(id));
        stats.putAll(adminQueryRepository.userCouponStats(id));
        stats.put("points", adminQueryRepository.userPointsOf(id));
        return stats;
    }

    /** 排序白名单在只读查询里守住，这里只把页面传来的列名与方向折叠成 Sort */
    private static Sort userSort(String sort, String order) {
        String key = sort == null || sort.isBlank() ? "createdAt" : sort.trim();
        Sort.Direction direction = "asc".equalsIgnoreCase(order == null ? "" : order.trim())
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(direction, key);
    }

    /** 结束日按当天 23:59:59 收口，否则「到今天」会把今天注册的账号漏掉 */
    private static LocalDateTime dateOf(String raw, boolean endOfDay) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            LocalDate day = LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
            return endOfDay ? day.atTime(23, 59, 59) : day.atStartOfDay();
        } catch (RuntimeException e) {
            throw new BusinessException("时间格式不正确，应为 yyyy-MM-dd");
        }
    }

    /**
     * G17：把当页账号「最近一次禁/启用」的原因一次带回，列表页不必逐行查审计
     */
    private Map<String, Object> latestDispositions(List<User> users) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (users.isEmpty()) {
            return out;
        }
        Set<String> wanted = users.stream().map(user -> String.valueOf(user.getId())).collect(Collectors.toSet());
        for (Object[] row : adminQueryRepository.recentDispositions()) {
            String userId = userIdOf(String.valueOf(row[0] == null ? "" : row[0]));
            // recentDispositions 已按时间倒序，同一个账号只留最新的一条
            if (userId == null || !wanted.contains(userId) || out.containsKey(userId)) {
                continue;
            }
            String detail = String.valueOf(row[4] == null ? "" : row[4]);
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("createdAt", row[1]);
            item.put("operatorName", row[2]);
            item.put("resultMsg", row[3]);
            item.put("reason", reasonOf(detail));
            item.put("targetStatus", jsonStringField(detail, "status"));
            out.put(userId, item);
        }
        return out;
    }

    /** 详情页只关心一个账号，直接走按 id 的留痕查询，避免全表回扫 */
    private Map<String, Object> firstDispositionOf(UUID id) {
        for (Object[] row : adminQueryRepository.userDispositionLogs(id, 20)) {
            String detail = String.valueOf(row[5] == null ? "" : row[5]);
            if (!"启停".equals(String.valueOf(row[1]))) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("createdAt", row[0]);
            item.put("operatorName", row[2]);
            item.put("resultMsg", row[4]);
            item.put("reason", reasonOf(detail));
            item.put("targetStatus", jsonStringField(detail, "status"));
            return item;
        }
        return Map.of();
    }

    /** /api/admin/users/{id}/status?x=y → id */
    private static String userIdOf(String uri) {
        String path = uri.split("\\?")[0];
        if (!path.startsWith("/api/admin/users/") || !path.endsWith("/status")) {
            return null;
        }
        String[] segments = path.split("/");
        // 空串开头：["", "api", "admin", "users", "{id}", "status"]
        return segments.length == 6 ? segments[4] : null;
    }

    /** 审计请求体里只取指定键，其余参数不外泄 */
    private static String reasonOf(String detail) {
        return jsonStringField(detail, "reason");
    }

    /**
     * 手写取值而不是引 JSON 解析器：审计 detail 可能是被 1800 字符截断的半截 JSON，
     * 解析器会直接抛异常，而这里至少还能把已经写进去的原因捞出来
     */
    private static String jsonStringField(String json, String key) {
        if (json == null || json.isBlank()) {
            return "";
        }
        int index = json.indexOf("\"" + key + "\"");
        if (index < 0) {
            return "";
        }
        int colon = json.indexOf(':', index);
        int firstQuote = json.indexOf('"', colon);
        if (colon < 0 || firstQuote < 0) {
            return "";
        }
        int end = json.indexOf('"', firstQuote + 1);
        return end < 0 ? json.substring(firstQuote + 1) : json.substring(firstQuote + 1, end);
    }

    private void requireNotSelf(UUID id, HttpSession session, String message) {
        User loginUser = CurrentUser.require(session);
        if (loginUser.getId().equals(id)) {
            throw new BusinessException(message);
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
