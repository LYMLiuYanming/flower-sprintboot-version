package org.liuym.flowerv1springboot.common;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 触达事件（U02）：业务侧只负责把「发生了什么」丢出来，落库、策略判定、幂等都在监听侧。
 *
 * <p>之所以用事件而不是让订单/券/工单的服务直接调消息 Service：
 * 触达是旁路能力，它挂了不能把下单事务带下去；用事件把两侧解耦后，
 * 主链路只需要一次 {@code publishEvent}，剩下的失败都由监听器自己吞掉并告警。
 *
 * @param userId       收件人
 * @param bizType      事件类型码（见 {@link MessageKeys#EVENT_LABELS}）
 * @param bizId        业务主键，与 bizType 一起构成幂等键
 * @param templateCode 模板 code；为 null 时走 title/content 直发（后台群发用）
 * @param vars         模板变量；缺必填槽位时整条不落库（U07）
 * @param title        直发标题（templateCode 为空时使用）
 * @param content      直发正文（templateCode 为空时使用）
 * @param linkUrl      站内跳转，越权地址会被丢弃并计数（U05）
 * @param dueAt        延时投递时刻；非空时进 scheduled_message（U20/U21）
 * @param customised   U22：false 表示文案未命中知识库，页面要标注「未定制」
 * @param qualifier    幂等键的附加维度：同一业务主键要发多条时用它区分
 *                     （养护提醒的第 2/4/7 天、群发的活动 id）
 */
public record NotificationEvent(UUID userId, String bizType, Object bizId, String templateCode,
                                Map<String, Object> vars, String title, String content,
                                String linkUrl, LocalDateTime dueAt, Boolean customised, String qualifier) {

    public static Builder builder(UUID userId, String bizType, Object bizId) {
        return new Builder(userId, bizType, bizId);
    }

    /** 构造器：事件字段多且大部分可选，链式比十参构造器不容易填错位 */
    public static final class Builder {
        private final UUID userId;
        private final String bizType;
        private final Object bizId;
        private String templateCode;
        private Map<String, Object> vars = Map.of();
        private String title;
        private String content;
        private String linkUrl;
        private LocalDateTime dueAt;
        private Boolean customised;
        private String qualifier;

        private Builder(UUID userId, String bizType, Object bizId) {
            this.userId = userId;
            this.bizType = bizType;
            this.bizId = bizId;
        }

        public Builder template(String templateCode) {
            this.templateCode = templateCode;
            return this;
        }

        public Builder template(String templateCode, Map<String, Object> vars) {
            this.templateCode = templateCode;
            this.vars = vars == null ? Map.of() : vars;
            return this;
        }

        public Builder direct(String title, String content) {
            this.title = title;
            this.content = content;
            return this;
        }

        public Builder link(String linkUrl) {
            this.linkUrl = linkUrl;
            return this;
        }

        /** 延时投递：养护提醒第 2/4/7 天就靠它（U20） */
        public Builder dueAt(LocalDateTime dueAt) {
            this.dueAt = dueAt;
            return this;
        }

        public Builder customised(boolean customised) {
            this.customised = customised;
            return this;
        }

        /** 同一业务主键要发多条时用：养护提醒按天、群发按活动 id */
        public Builder qualifier(Object qualifier) {
            this.qualifier = qualifier == null ? null : String.valueOf(qualifier);
            return this;
        }

        public NotificationEvent build() {
            return new NotificationEvent(userId, bizType, bizId, templateCode, vars, title, content,
                    linkUrl, dueAt, customised, qualifier);
        }
    }
}
