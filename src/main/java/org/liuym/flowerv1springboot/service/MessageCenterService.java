package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.dto.NotificationDtos;
import org.liuym.flowerv1springboot.vo.NotificationViews;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 消息中心读侧（U03/U04/U05/U07/U08/U10/U21/U23）：站内信查询、已读、偏好与后台可见的台账。
 *
 * <p>写侧一律在 {@link MessageDispatchService}，两者分开的理由是事务边界不同：
 * 读侧全是只查，写侧要跑在事件提交之后的独立事务里。
 */
public interface MessageCenterService {

    /** U03 铃铛角标：未读数 + 最近 5 条；未登录由控制层直接返回空角标 */
    NotificationViews.UnreadBadge unreadBadge(UUID userId);

    /** U03 只取未读数：30 秒轮询用的最小开销版本 */
    long unreadCount(UUID userId);

    /** U04 列表：category 空串不限，unreadOnly 只看未读 */
    Page<NotificationViews.MessageView> listForUser(UUID userId, String category, boolean unreadOnly,
                                                    int page, int limit);

    /** U04 单条已读：返回真实变更行数，0 表示本来就是已读（页面不必报错） */
    int markRead(UUID messageId, UUID userId);

    /** U04 全部已读：category 空串表示不限类别 */
    int markAllRead(UUID userId, String category);

    /** 删除自己的消息：只有属于该用户的行能删 */
    boolean deleteOwn(UUID messageId, UUID userId);

    /** U08 偏好矩阵：类别 × 渠道 + 免打扰时段 */
    NotificationViews.PreferenceView preferences(UUID userId);

    /**
     * U08 保存偏好：只有 inbox 允许打开。
     * 页面提交 sms/email=true 不会静默忽略，而是明确拒绝并说明「通道未开通」，
     * 否则用户会以为自己已经打开了短信提醒。
     */
    NotificationViews.PreferenceView savePreferences(UUID userId, NotificationDtos.PrefMatrixForm form);

    /** U23 退订营销：一步关掉 marketing×inbox 与档案里的营销开关 */
    NotificationViews.PreferenceView unsubscribeMarketing(UUID userId, boolean unsubscribe);

    /** U07 模板列表（后台） */
    Page<NotificationViews.TemplateView> templates(String category, String keyword, boolean enabledOnly,
                                                    int page, int limit);

    /** U07 模板保存：槽位与 varKeys 不一致时拒绝，落库前就拦住发出去的半成品文案 */
    NotificationViews.TemplateView saveTemplate(UUID id, NotificationDtos.TemplateForm form, String operator);

    /** U07 启停模板：返回真实变更条数 */
    int toggleTemplate(UUID id, boolean enabled);

    /** U21 待发清单（后台） */
    List<NotificationViews.ScheduledView> scheduled(String status, String bizType, int limit);

    /** U06 事件接入清单（后台）：八类事件的近 24 小时触达/抑制数 */
    List<NotificationViews.EventIngestRow> eventIngestBoard();

    /** U05 越权跳转统计（后台） */
    NotificationViews.GuardStat guardStat();

    /** 后台触达明细 */
    Page<NotificationViews.MessageView> searchForAdmin(NotificationDtos.MessageQuery query);

    /** U23 群发台账 */
    Page<NotificationViews.CampaignView> campaigns(int page, int limit);

    /** 概览卡：保留期内总量、未读总数、待发数 */
    Map<String, Object> overview();
}
