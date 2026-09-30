package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.vo.SupportViews.SessionView;

import java.util.List;
import java.util.UUID;

/**
 * 登录会话台账服务（D18）：记录登录、列出设备、退出其他设备。
 */
public interface SessionService {

    /**
     * 登录成功后登记一条会话并返回其 token（调用方需写回 HttpSession，作为「当前设备」标识）。
     *
     * @param userId        登录用户
     * @param httpSessionId 容器分配的 HttpSession id
     * @param ip            客户端 IP
     * @param userAgent     User-Agent 原文（超长截断）
     * @param rememberMe    本次是否签发记住我 Cookie
     */
    UUID recordLogin(UUID userId, String httpSessionId, String ip, String userAgent, boolean rememberMe);

    /** 当前会话仍有效则刷新最近活动时间（用于「最近使用」排序） */
    void touch(UUID token);

    /** 列出某用户的有效设备；currentToken 命中的那条标记 current=true */
    List<SessionView> listDevices(UUID userId, UUID currentToken);

    /**
     * 退出其他设备：撤销除 currentToken 外的全部会话，返回受影响条数。
     * currentToken 为 null 时等价于全部撤销（如改密后强制下线，但当前会话由业务另行处理）。
     */
    int revokeOthers(UUID userId, UUID currentToken);

    /** 撤销当前会话（主动退出登录时调用） */
    void revoke(UUID token);

    /** 判定给定 token 是否仍有效（存在且未撤销），续登/鉴权时把关 */
    boolean isActive(UUID token);
}
