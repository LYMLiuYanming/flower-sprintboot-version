package org.liuym.flowerv1springboot.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 触达发布入口（U02）：业务侧调用它把「发生了什么」变成一个站内信事件。
 *
 * <p>三条口径写死在这里，免得每个调用点各自理解：
 * <ol>
 *   <li><b>不进主事务</b>：只 {@code publishEvent}，落库由
 *       {@code NotificationEventListener} 在事务提交后异步做。站内信是旁路能力，
 *       它挂了不能把下单、发货、退款的主链路带下去——与第一轮 {@code OrderServiceImpl#trace}
 *       的容错口径一致（吞异常 + 只告警）。</li>
 *   <li><b>幂等</b>：调用方只传业务主键，dedup_key 由 {@link MessageKeys} 统一生成，
 *       重复调用撞唯一索引即丢弃。</li>
 *   <li><b>不承诺即时</b>：返回 void。要拿到「是否真的落库」请查消息列表或后台触达明细，
 *       发布方不需要知道结果。</li>
 * </ol>
 *
 * <p>八个事件类型（U06）都给了显式方法：支付成功、发货、签收、退款结果、券到期、
 * 礼品卡到期/转赠、订阅提醒、工单回复。前四类与券到期由补采任务按时间窗扫描既有表产生，
 * 礼品卡与订阅在 M/N 组的服务里调这里的方法接入。
 */
@Component
public class MessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(MessagePublisher.class);

    /** 事件类型码：与 MessageKeys.EVENT_LABELS、message_template.code 同源 */
    public static final String EVENT_ORDER_PAID = "order_paid";
    public static final String EVENT_ORDER_SHIPPED = "order_shipped";
    public static final String EVENT_ORDER_DELIVERED = "order_delivered";
    public static final String EVENT_REFUND_RESULT = "refund_result";
    public static final String EVENT_COUPON_EXPIRING = "coupon_expiring";
    public static final String EVENT_CARD_EXPIRING = "card_expiring";
    public static final String EVENT_CARD_TRANSFERRED = "card_transferred";
    public static final String EVENT_SUBSCRIPTION_DUE = "subscription_due";
    public static final String EVENT_TICKET_REPLIED = "ticket_replied";
    public static final String EVENT_TICKET_RESOLVED = "ticket_resolved";
    public static final String EVENT_CARE_REMINDER = "care_reminder";
    public static final String EVENT_BROADCAST = "broadcast";

    /** 养护提醒的天数口径（U20）：签收后第 2 / 4 / 7 天 */
    public static final int[] CARE_REMINDER_DAYS = {2, 4, 7};

    private final ApplicationEventPublisher eventPublisher;

    public MessagePublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * 发布一个触达事件：任何异常都只记 WARN，绝不向调用方抛出。
     *
     * <p>{@code publishEvent} 本身对 @TransactionalEventListener 只是登记，
     * 真正的落库在事务提交之后；所以这里连 try-catch 都要留着——
     * 发布器 bean 被误配成同步执行时，这条 catch 就是主链路的最后一道保险。
     */
    public void publish(NotificationEvent event) {
        if (event == null || event.userId() == null || event.bizType() == null || event.bizType().isBlank()) {
            log.warn("触达事件缺少收件人或事件类型，已忽略：{}", event);
            return;
        }
        try {
            eventPublisher.publishEvent(event);
        } catch (RuntimeException e) {
            log.warn("触达事件发布失败 type={} bizId={} user={}: {}",
                    event.bizType(), event.bizId(), event.userId(), Masking.brief(e));
        }
    }

    /** 模板事件：最常见的一类，变量缺了会在监听侧整条丢弃（U07） */
    public void publishTemplate(UUID userId, String bizType, Object bizId, String templateCode,
                               Map<String, Object> vars, String linkUrl) {
        publish(NotificationEvent.builder(userId, bizType, bizId)
                .template(templateCode, vars)
                .link(linkUrl)
                .build());
    }

    /** 直发事件：后台群发与养护提醒文案已在发布侧算好，不再走模板插值 */
    public void publishDirect(UUID userId, String bizType, Object bizId, String title, String content,
                              String linkUrl, boolean customised) {
        publish(NotificationEvent.builder(userId, bizType, bizId)
                .direct(title, content)
                .link(linkUrl)
                .customised(customised)
                .build());
    }

    /** 延时事件（U20）：dueAt 非空时进 scheduled_message，由抢占任务到点投递 */
    public void publishDelayed(UUID userId, String bizType, Object bizId, Object qualifier,
                               LocalDateTime dueAt, String title, String content,
                               String linkUrl, boolean customised) {
        if (dueAt == null) {
            publishDirect(userId, bizType, bizId, title, content, linkUrl, customised);
            return;
        }
        publish(NotificationEvent.builder(userId, bizType, bizId)
                .direct(title, content)
                .link(linkUrl)
                .dueAt(dueAt)
                .qualifier(qualifier)
                .customised(customised)
                .build());
    }

    /* ---- U06 八个事件类型的显式入口：调用点只传业务对象需要的字段，口径集中在这一处 ---- */

    public void orderPaid(UUID userId, UUID orderId, String orderNo, Object payAmount, String deliveryHint) {
        publishTemplate(userId, EVENT_ORDER_PAID, orderId, EVENT_ORDER_PAID, Map.of(
                "orderNo", nullToEmpty(orderNo),
                "amount", nullToEmpty(payAmount),
                "deliveryHint", nullToEmpty(deliveryHint)), "/user/orders");
    }

    public void orderShipped(UUID userId, UUID orderId, String orderNo, String expressCompany, String expressNo,
                             String expectText, String receiverName) {
        publishTemplate(userId, EVENT_ORDER_SHIPPED, orderId, EVENT_ORDER_SHIPPED, Map.of(
                "orderNo", nullToEmpty(orderNo),
                "expressCompany", nullToEmpty(expressCompany),
                "expressNo", nullToEmpty(expressNo),
                "expectText", nullToEmpty(expectText),
                "receiverName", nullToEmpty(receiverName)), "/user/orders");
    }

    public void orderDelivered(UUID userId, UUID orderId, String orderNo, String receiverName) {
        publishTemplate(userId, EVENT_ORDER_DELIVERED, orderId, EVENT_ORDER_DELIVERED, Map.of(
                "orderNo", nullToEmpty(orderNo),
                "receiverName", nullToEmpty(receiverName)), "/user/orders");
    }

    public void refundResult(UUID userId, UUID orderId, String orderNo, String resultText,
                             Object amount, String note) {
        publishTemplate(userId, EVENT_REFUND_RESULT, orderId, EVENT_REFUND_RESULT, Map.of(
                "orderNo", nullToEmpty(orderNo),
                "resultText", nullToEmpty(resultText),
                "amount", nullToEmpty(amount),
                "note", nullToEmpty(note)), "/user/orders");
    }

    public void couponExpiring(UUID userId, UUID userCouponId, String couponName,
                               String expireDate, long daysLeft) {
        publishTemplate(userId, EVENT_COUPON_EXPIRING, userCouponId, EVENT_COUPON_EXPIRING, Map.of(
                "couponName", nullToEmpty(couponName),
                "expireDate", nullToEmpty(expireDate),
                "daysLeft", daysLeft), "/user/coupons");
    }

    /** 礼品卡到期（M 组调用）：卡号只给脱敏后的形态，模板里也不放完整卡号 */
    public void giftCardExpiring(UUID userId, UUID cardId, String maskedCardNo,
                                 Object balance, String expireDate) {
        publishTemplate(userId, EVENT_CARD_EXPIRING, cardId, EVENT_CARD_EXPIRING, Map.of(
                "cardNo", nullToEmpty(maskedCardNo),
                "balance", nullToEmpty(balance),
                "expireDate", nullToEmpty(expireDate)), "/user/wallet");
    }

    /** 礼品卡转赠（M 组调用） */
    public void giftCardTransferred(UUID userId, UUID cardId, String maskedCardNo, String receiverName) {
        publishTemplate(userId, EVENT_CARD_TRANSFERRED, cardId, EVENT_CARD_TRANSFERRED, Map.of(
                "cardNo", nullToEmpty(maskedCardNo),
                "receiver", Masking.name(receiverName)), "/user/wallet");
    }

    /** 订阅提醒（N 组调用） */
    public void subscriptionDue(UUID userId, UUID subscriptionId, String planName,
                                String nextDate, String dayText) {
        publishTemplate(userId, EVENT_SUBSCRIPTION_DUE, subscriptionId, EVENT_SUBSCRIPTION_DUE, Map.of(
                "planName", nullToEmpty(planName),
                "nextDate", nullToEmpty(nextDate),
                "dayText", nullToEmpty(dayText)), "/user/subscription");
    }

    /** 工单回复（U06，工单服务内部调用） */
    public void ticketReplied(UUID userId, UUID ticketId, String ticketNo, String title, String excerpt) {
        publishTemplate(userId, EVENT_TICKET_REPLIED, ticketId, EVENT_TICKET_REPLIED, Map.of(
                "ticketNo", nullToEmpty(ticketNo),
                "title", nullToEmpty(title),
                "excerpt", nullToEmpty(excerpt)), "/user/tickets/" + ticketId);
    }

    /** 工单解决通知（U13/U24） */
    public void ticketResolved(UUID userId, UUID ticketId, String ticketNo, String solution) {
        publishTemplate(userId, EVENT_TICKET_RESOLVED, ticketId, EVENT_TICKET_RESOLVED, Map.of(
                "ticketNo", nullToEmpty(ticketNo),
                "solution", nullToEmpty(solution)), "/user/tickets/" + ticketId);
    }

    private static Object nullToEmpty(Object value) {
        return value == null ? "" : value;
    }
}
