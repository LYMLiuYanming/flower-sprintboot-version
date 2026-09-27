package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Pages;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.UserDtos;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.service.UserService;
import org.liuym.flowerv1springboot.vo.SupportViews.UserView;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 后台用户管理：输出一律走 UserView（不含 password），
 * 管理员不能禁用自己，重置密码需填理由并强制用户下次登录改密
 */
@RestController
@RequestMapping("/api/admin/users")
@Tag(name = "后台 · 用户管理")
public class UserAdminController {

    private static final Map<String, String> STATUS_LABEL =
            Map.of(User.STATUS_ACTIVE, "正常", "inactive", "已禁用", "locked", "已锁定");

    private final UserService userService;

    public UserAdminController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public Result<List<UserView>> list(@RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "10") int limit,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String userType,
                                       @RequestParam(required = false) String status) {
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        Page<User> result = userService.search(kw, trimToNull(userType), trimToNull(status), Pages.of(page, limit));
        return Result.page(result.getContent().stream().map(UserView::from).toList(), result.getTotalElements());
    }

    @GetMapping("/{id}")
    public Result<UserView> detail(@PathVariable UUID id) {
        return userService.findById(id).map(u -> Result.ok(UserView.from(u)))
                .orElseGet(() -> Result.notFound("用户不存在"));
    }

    @PostMapping("/{id}/status")
    public Result<UserView> changeStatus(@PathVariable UUID id,
                                         @Valid @RequestBody UserDtos.AdminUserStatusRequest request,
                                         HttpSession session) {
        requireNotSelf(id, session, "不能修改自己的账号状态");
        if (!STATUS_LABEL.containsKey(request.status())) {
            throw new BusinessException("账号状态取值不合法");
        }
        if (!userService.updateStatus(id, request.status())) {
            throw BusinessException.notFound("用户不存在");
        }
        return Result.ok("状态已更新为" + STATUS_LABEL.get(request.status()),
                UserView.from(userService.findById(id).orElseThrow()));
    }

    /**
     * 管理员重置密码：返回随机初始密码，并要求该用户下次登录必须改密
     */
    @PostMapping("/{id}/reset-password")
    public Result<Map<String, String>> resetPassword(@PathVariable UUID id,
                                                     @Valid @RequestBody UserDtos.ResetPasswordRequest request,
                                                     HttpSession session) {
        requireNotSelf(id, session, "请使用个人中心修改自己的密码");
        String initial = userService.resetPasswordByAdmin(id, request.newPassword());
        return Result.ok("密码已重置，请通知用户使用初始密码登录", Map.of("initialPassword", initial));
    }

    @GetMapping("/count")
    public Result<Long> count() {
        return Result.ok(userService.count());
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
