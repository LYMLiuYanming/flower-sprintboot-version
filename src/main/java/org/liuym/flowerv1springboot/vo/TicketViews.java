package org.liuym.flowerv1springboot.vo;

import org.liuym.flowerv1springboot.common.Masked;
import org.liuym.flowerv1springboot.common.TicketFlowPolicy;
import org.liuym.flowerv1springboot.common.TicketSlaPolicy;
import org.liuym.flowerv1springboot.model.Ticket;
import org.liuym.flowerv1springboot.model.TicketAgent;
import org.liuym.flowerv1springboot.model.TicketMessage;
import org.liuym.flowerv1springboot.model.TicketSlaRule;
import org.liuym.flowerv1springboot.model.FaqEntry;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工单出参（U12/U15/U16/U17/U24/U25/U26）。
 *
 * <p>所有中文标签、SLA 剩余分钟、逾期与「即将超时」判定都在服务端算完，
 * 页面只渲染：这样顾客侧与后台侧看到的是同一套口径，不会两套账。
 *
 * <p>{@code contactPhone} 与关联订单的收货电话都按 {@link Masked} 口径脱敏（U16），
 * 任何视图里都没有口令类字段。
 */
public final class TicketViews {

    private TicketViews() {
    }

    /** U15 队列行 */
    public record Brief(UUID id, String ticketNo, String category, String categoryLabel,
                        String title, String status, String statusLabel, String priority, String priorityLabel,
                        String assigneeName, LocalDateTime createdAt, LocalDateTime resolveDueAt,
                        Long slaLeftMinutes, String deadlineState, boolean overdue, boolean approaching,
                        boolean escalated, Integer reopenCount, Integer satisfaction,
                        String compensationType, Boolean hasUnreadReply, UUID orderId) {

        public static Brief of(Ticket t, LocalDateTime now, boolean hasUnreadReply) {
            String state = TicketSlaPolicy.deadlineState(t, now);
            return new Brief(t.getId(), t.getTicketNo(), t.getCategory(), TicketFlowPolicy.categoryLabel(t.getCategory()),
                    t.getTitle(), t.getStatus(), TicketFlowPolicy.statusLabel(t.getStatus()),
                    t.getPriority(), TicketFlowPolicy.priorityLabel(t.getPriority()), t.getAssigneeName(),
                    t.getCreatedAt(), t.getResolveDueAt(), TicketSlaPolicy.minutesLeft(t, now), state,
                    "overdue".equals(state), "warning".equals(state), Boolean.TRUE.equals(t.getEscalated()),
                    t.getReopenCount(), t.getSatisfaction(), t.getCompensationType(), hasUnreadReply, t.getOrderId());
        }
    }

    /** U16 时间线条目：顾客侧与后台侧共用，内部备注只在后台侧出现 */
    public record Entry(UUID id, String authorType, String authorLabel, String authorName, String content,
                        String attachments, LocalDateTime createdAt, boolean internalNote) {

        private static final Map<String, String> AUTHOR_LABELS = Map.of(
                TicketMessage.AUTHOR_CUSTOMER, "顾客",
                TicketMessage.AUTHOR_AGENT, "客服",
                TicketMessage.AUTHOR_SYSTEM, "系统");

        public static Entry of(TicketMessage m) {
            return new Entry(m.getId(), m.getAuthorType(),
                    AUTHOR_LABELS.getOrDefault(m.getAuthorType(), m.getAuthorType()),
                    m.getAuthorName(), m.getContent(), m.getAttachments(), m.getCreatedAt(),
                    Boolean.TRUE.equals(m.getInternalNote()));
        }
    }

    /** U26 关联订单快照：电话脱敏，地址与口令一律不出网 */
    public record OrderView(UUID orderId, String orderNo, String status, String statusLabel,
                            BigDecimal payAmount, BigDecimal refundAmount, String refundStatus,
                            LocalDateTime createdAt, LocalDateTime payTime, LocalDateTime expectedArriveAt,
                            @Masked(Masked.Kind.NAME) String receiverName,
                            @Masked(Masked.Kind.PHONE) String receiverPhone,
                            String expressCompany, String expressNo) {
    }

    /** U16 详情 */
    public record Detail(Brief brief,
                         @Masked(Masked.Kind.PHONE) String contactPhone,
                         String description, String images, String solution,
                         LocalDateTime firstResponseDueAt, LocalDateTime firstResponseAt,
                         LocalDateTime resolvedAt, LocalDateTime closedAt,
                         Long firstResponseMinutes, Long resolveMinutes,
                         String escalateNote, LocalDateTime escalatedAt,
                         String compensationType, String compensationRef, String compensationNote,
                         LocalDateTime compensatedAt,
                         Integer satisfaction, String satisfactionNote, LocalDateTime satisfactionAt,
                         String sourceType, String slaText,
                         List<Entry> timeline, OrderView order,
                         List<String> allowedActions, String nextStepHint,
                         boolean rateable, boolean reopenable) {
    }

    /** U19 FAQ 命中 */
    public record FaqView(UUID id, String question, String answer, String category, String categoryLabel,
                          Integer hitCount, Integer sortOrder) {

        public static FaqView of(FaqEntry f) {
            return new FaqView(f.getId(), f.getQuestion(), f.getAnswer(), f.getCategory(),
                    TicketFlowPolicy.categoryLabel(f.getCategory()), f.getHitCount(), f.getSortOrder());
        }
    }

    /** U14 SLA 规则行 */
    public record SlaRuleView(UUID id, String category, String categoryLabel, String priority, String priorityLabel,
                              Integer firstResponseMinutes, Integer resolveMinutes,
                              String firstResponseText, String resolveText, Boolean enabled, String remark) {

        public static SlaRuleView of(TicketSlaRule r) {
            return new SlaRuleView(r.getId(), r.getCategory(), TicketFlowPolicy.categoryLabel(r.getCategory()),
                    r.getPriority(), TicketFlowPolicy.priorityLabel(r.getPriority()),
                    r.getFirstResponseMinutes(), r.getResolveMinutes(),
                    humanMinutes(r.getFirstResponseMinutes()), humanMinutes(r.getResolveMinutes()),
                    Boolean.TRUE.equals(r.getEnabled()), r.getRemark());
        }
    }

    /** 分钟数转「2 小时 30 分」：后台列表与工单详情共用，页面不再自己除 60 */
    public static String humanMinutes(Integer minutes) {
        if (minutes == null || minutes <= 0) {
            return "未设置";
        }
        int days = minutes / (60 * 24);
        int hours = minutes % (60 * 24) / 60;
        int mins = minutes % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append(" 天");
        }
        if (hours > 0) {
            sb.append(sb.isEmpty() ? "" : " ").append(hours).append(" 小时");
        }
        if (mins > 0 || sb.isEmpty()) {
            sb.append(sb.isEmpty() ? "" : " ").append(mins).append(" 分");
        }
        return sb.toString();
    }

    /** U26 客服账号行 */
    public record AgentView(UUID userId, String displayName, String username, boolean supervisor, boolean enabled,
                            long assignedOpen, long resolvedTotal) {

        public static AgentView of(TicketAgent a, String username, long assignedOpen, long resolvedTotal) {
            return new AgentView(a.getUserId(), a.getDisplayName(), username, a.isSupervisor(),
                    Boolean.TRUE.equals(a.getEnabled()), assignedOpen, resolvedTotal);
        }
    }

    /** U25 看板：分位数与达标率都在 SQL 里算，页面不参与统计 */
    public record Dashboard(LocalDateTime from, LocalDateTime to, long createdTotal, long settledTotal,
                            long onTimeTotal, double slaComplianceRate,
                            double firstResponseP50Minutes, double firstResponseP90Minutes,
                            double firstResponseAvgMinutes, long firstResponseSamples,
                            double resolveP50Minutes, double resolveP90Minutes, double resolveAvgMinutes,
                            long resolveSamples,
                            long reopenedTotal, double reopenRate,
                            double averageSatisfaction, long ratedTotal,
                            long overdueOpen, long approachingOpen,
                            List<Map<String, Object>> categoryDistribution,
                            List<Map<String, Object>> priorityDistribution,
                            List<Map<String, Object>> statusDistribution) {
    }

    /** 字典输出：类型/优先级/状态的 code→中文一次给全，前端不再维护第二份 */
    public record Dictionary(List<Map<String, String>> categories, List<Map<String, String>> priorities,
                             List<Map<String, String>> statuses, List<String> customerActions) {
    }
}
