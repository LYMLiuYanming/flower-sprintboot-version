package org.liuym.flowerv1springboot.model;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 退款申请单状态机（C19）：申请 → 审核中 → 已退款，任一审前节点都可驳回或由用户撤销。
 *
 * <p>与 {@link OrderStatus} 分开维护：退款在途时订单本身仍是「已付款/处理中」，
 * 合并成一个状态会把后台的「待发货」队列打乱。
 */
public enum RefundStatus {

    PENDING("pending", "退款申请中"),
    REVIEWING("reviewing", "退款审核中"),
    REFUNDED("refunded", "已退款"),
    REJECTED("rejected", "退款被驳回"),
    REVOKED("revoked", "退款已撤销");

    /** 在途状态：一笔订单同时只允许一张，唯一索引与撤销判定都用它 */
    public static final Set<RefundStatus> OPEN = EnumSet.of(PENDING, REVIEWING);

    /** JPQL 的 IN 参数要 List，集合类查询统一用它，避免各处再 newArrayList 一遍 */
    public static final List<RefundStatus> OPEN_LIST = List.of(PENDING, REVIEWING);

    /** 驳回与撤销都不算结论落地，允许用户重新申请 */
    public static final List<RefundStatus> REOPENABLE_LIST = List.of(REJECTED, REVOKED);

    private final String code;
    private final String label;

    RefundStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public boolean isOpen() {
        return OPEN.contains(this);
    }

    /** 用户能自己撤掉的只有还没出结论的申请 */
    public boolean isRevokable() {
        return isOpen();
    }

    /** 受理只认「申请中」；settle 只认「审核中」，跳过审核直接退款在状态机上不留口子 */
    public boolean canAccept() {
        return this == PENDING;
    }

    public boolean canSettle() {
        return this == REVIEWING;
    }

    public boolean canReject() {
        return isOpen();
    }

    public static Set<RefundStatus> allowedNext(RefundStatus from) {
        return switch (from) {
            case PENDING -> EnumSet.of(REVIEWING, REJECTED, REVOKED);
            case REVIEWING -> EnumSet.of(REFUNDED, REJECTED, REVOKED);
            default -> EnumSet.noneOf(RefundStatus.class);
        };
    }

    /**
     * 给出「退款申请已是「已退款」，不能重复确认」这类可执行文案（C10 同口径），
     * 而不是一句「操作失败」让用户反复点。
     */
    public static String denial(RefundStatus from, String action) {
        if (from == null) {
            return "本单没有退款申请，" + action + "无效";
        }
        return switch (from) {
            case REFUNDED -> "退款已到账，不能重复" + action;
            case REJECTED -> "退款申请已被驳回，如需退款请重新提交申请";
            case REVOKED -> "退款申请已撤销，如需退款请重新提交申请";
            case REVIEWING -> "退款正在门店审核中，请等待审核结论后再" + action;
            case PENDING -> "退款申请还需门店受理，受理后才能" + action;
        };
    }

    public static RefundStatus fromCode(String code) {
        return Arrays.stream(values())
                .filter(s -> s.code.equalsIgnoreCase(code) || s.name().equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知的退款状态：" + code));
    }
}
