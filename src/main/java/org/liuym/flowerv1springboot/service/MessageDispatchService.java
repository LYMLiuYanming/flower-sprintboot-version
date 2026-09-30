package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.common.NotificationEvent;
import org.liuym.flowerv1springboot.dto.NotificationDtos;
import org.liuym.flowerv1springboot.vo.NotificationViews;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 触达引擎（U02/U05/U06/U07/U09/U10/U20/U21/U23）：把事件变成库里的一行站内信。
 *
 * <p>与 {@link MessageCenterService} 的分工是「只写 / 只读」：
 * 写侧跑在事件提交之后的独立事务里，读侧多是查询，合在一起会让读接口的事务边界跟着一起变复杂。
 */
public interface MessageDispatchService {

    /** 触达结果码：后台与单测都按它断言，而不是靠日志字串 */
    enum Outcome {
        /** 已落 user_message */
        DELIVERED,
        /** 已进 scheduled_message，到点再投 */
        SCHEDULED,
        /** 被策略抑制：退订、周末营销、当日上限、免打扰 */
        SUPPRESSED,
        /** 幂等命中：同一业务事件已经触达过了 */
        DUPLICATE,
        /** 模板或参数不合法：缺槽位、模板停用、渲染后文案为空、收件人不存在 */
        INVALID
    }

    /**
     * @param outcome 结果码
     * @param reason  人话原因（INVALID/SUPPRESSED 时必有）
     * @param count   实际写入行数（0 或 1）
     * @param policyReason 被抑制时的策略分类，群发台账按它分列计数
     */
    record DispatchResult(Outcome outcome, String reason, int count,
                          org.liuym.flowerv1springboot.common.NotificationPolicy.Reason policyReason) {

        public boolean delivered() {
            return count > 0;
        }

        public static DispatchResult of(Outcome outcome, String reason) {
            return new DispatchResult(outcome, reason, 0,
                    org.liuym.flowerv1springboot.common.NotificationPolicy.Reason.NONE);
        }

        public static DispatchResult suppressed(String reason,
                                                org.liuym.flowerv1springboot.common.NotificationPolicy.Reason kind) {
            return new DispatchResult(Outcome.SUPPRESSED, reason, 0, kind);
        }

        public static DispatchResult delivered(int count) {
            return new DispatchResult(Outcome.DELIVERED, null, count,
                    org.liuym.flowerv1springboot.common.NotificationPolicy.Reason.NONE);
        }
    }

    /** 事件入口（U02）：模板渲染 → 白名单 → 策略判定 → 幂等落库；不抛异常，只回结果码 */
    DispatchResult dispatch(NotificationEvent event);

    /** U21 到点投递：条件 UPDATE 抢占后落库，返回本轮真实新增的消息条数 */
    int deliverDueScheduled(int batchLimit);

    /** U20/U22：签收事件生成第 2/4/7 天养护提醒，返回排期行数 */
    int scheduleCareReminders(UUID userId, UUID orderId, String orderNo, LocalDateTime deliveredAt);

    /** U10 保留期清理：按用户 + 月份 + 类别归档计数后物理删除 */
    NotificationViews.RetentionResult cleanupExpiredMessages();

    /** U23 群发：dryRun 时只回统计、一行都不写 */
    NotificationViews.BroadcastResult broadcast(NotificationDtos.BroadcastForm form, UUID operatorId, String operatorName);

    /** U23 人群预览：命中人数 + 各类策略会拦下多少 */
    Map<String, Object> previewAudience(String segmentCode, Integer windowDays);

    /** 分群下拉：code + label + 当前人数 */
    List<Map<String, Object>> audienceSegments();
}
