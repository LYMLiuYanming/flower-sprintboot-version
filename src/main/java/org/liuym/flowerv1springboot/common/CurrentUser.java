package org.liuym.flowerv1springboot.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.liuym.flowerv1springboot.model.User;

/**
 * 会话登录态读取工具：API 层统一入口，避免各处散落 (User) session.getAttribute(...) 强转
 */
public final class CurrentUser {

    public static final String SESSION_KEY = "loginUser";

    private CurrentUser() {
    }

    public static User of(HttpSession session) {
        Object attr = session.getAttribute(SESSION_KEY);
        return attr instanceof User user ? user : null;
    }

    /**
     * 需登录场景：未登录或被禁用时抛 401，由 GlobalExceptionHandler 转统一响应
     */
    public static User require(HttpSession session) {
        User user = of(session);
        if (user == null) {
            throw new BusinessException(401, "请先登录");
        }
        if (!"active".equals(user.getStatus())) {
            throw new BusinessException(403, "账号已被禁用或锁定");
        }
        return user;
    }

    public static User requireAdmin(HttpSession session) {
        User user = require(session);
        if (!User.TYPE_ADMIN.equals(user.getUserType())) {
            throw new BusinessException(403, "无管理员权限");
        }
        return user;
    }

    /**
     * 登录成功后重建会话上下文所需的回跳地址暂存
     */
    public static String savedTarget(HttpServletRequest request) {
        HttpSession session = request.getSession();
        Object target = session.getAttribute(REDIRECT_KEY);
        if (target != null) {
            session.removeAttribute(REDIRECT_KEY);
        }
        return target == null ? null : target.toString();
    }

    public static final String REDIRECT_KEY = "redirectAfterLogin";
}
