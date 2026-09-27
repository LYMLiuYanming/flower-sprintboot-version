package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.common.CouponPolicy;
import org.liuym.flowerv1springboot.dto.CouponDtos;
import org.liuym.flowerv1springboot.model.Coupon;
import org.liuym.flowerv1springboot.model.UserCoupon;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface CouponService {

    /* ---------- 后台 ---------- */

    List<Coupon> findAllForAdmin();

    Coupon findById(UUID id);

    Coupon createByForm(CouponDtos.Form form);

    Coupon updateByForm(UUID id, CouponDtos.Form form);

    boolean updateStatus(UUID id, String status);

    void delete(UUID id);

    /** 后台直发给指定用户，绕开领取窗口但仍受发行量约束 */
    UserCoupon issue(UUID couponId, UUID userId);

    /* ---------- 前台 ---------- */

    /** 可领取的券：启用 + 在领取窗口内 + 有余量 + 未超每人限领 */
    List<Coupon> findReceivable(UUID userId);

    UserCoupon claim(UUID userId, UUID couponId);

    /** 我的券包：读取前先把过期券落状态 */
    List<UserCoupon> findMine(UUID userId);

    /** 结算页可用券（含当前订单金额下的可抵数额，不可用为 null） */
    List<UserCoupon> findUsable(UUID userId);

    /** 本券在该订单行项目上的抵扣额；不满足门槛或已过期返回 null */
    BigDecimal discountOf(UserCoupon coupon, List<CouponPolicy.Line> lines);

    /**
     * 下单校验：券属于本人、未使用未过期、满足门槛，返回实际抵扣金额；不通过直接抛业务异常
     */
    BigDecimal requireUsableForOrder(UUID userId, UUID userCouponId, List<CouponPolicy.Line> lines);

    /** 核销（条件更新，返回 0 表示并发下已被用掉） */
    boolean consume(UUID userCouponId, UUID userId, UUID orderId);

    /** 订单取消/退款时回退 */
    void releaseByOrder(UUID orderId);
}
