package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.common.CouponPolicy;
import org.liuym.flowerv1springboot.dto.CouponDtos;
import org.liuym.flowerv1springboot.model.Coupon;
import org.liuym.flowerv1springboot.model.UserCoupon;
import org.liuym.flowerv1springboot.vo.CouponViews.CouponStatView;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface CouponService {

    /* ---------- 后台 ---------- */

    List<Coupon> findAllForAdmin();

    Coupon findById(UUID id);

    /** 促销位关联的券批量取回（E19），避免逐个 findById 打成一串查询 */
    List<Coupon> findByIds(List<UUID> ids);

    Coupon createByForm(CouponDtos.Form form);

    Coupon updateByForm(UUID id, CouponDtos.Form form);

    /** E11：复制新建。条款照抄、发行量清零、副本一律停用，避开「启用中券码唯一」的条件索引 */
    Coupon copy(UUID id);

    boolean updateStatus(UUID id, String status);

    /**
     * E12：带停用策略的上下架
     *
     * @param disablePolicy keep 保留已领券 / void 立即作废未使用券
     * @return 本次作废掉的持券张数
     */
    int updateStatus(UUID id, String status, String disablePolicy);

    void delete(UUID id);

    /** 后台直发给指定用户，绕开领取窗口但仍受发行量约束 */
    UserCoupon issue(UUID couponId, UUID userId);

    /** E13：券使用统计（领取数 / 核销数 / 核销率 / 已核销金额） */
    List<CouponStatView> statsForAdmin();

    /* ---------- 前台 ---------- */

    /** 可领取的券：启用 + 在领取窗口内 + 有余量 + 未超每人限领 */
    List<Coupon> findReceivable(UUID userId);

    /** 领券中心（E10）：只出「领券」场景的模板，categoryId 为空表示不限分类 */
    List<Coupon> findCenter(UUID userId, UUID categoryId);

    /** 此刻能不能被该用户领取；返回 null 表示可领，否则是给卡片上打的标记文案（E10） */
    String claimBlockReason(Coupon coupon, UUID userId, LocalDateTime now);

    UserCoupon claim(UUID userId, UUID couponId);

    /** 我的券包：读取前先把过期券与超时未领的转赠落状态 */
    List<UserCoupon> findMine(UUID userId);

    /** 结算页可用券 */
    List<UserCoupon> findUsable(UUID userId);

    /** E06：到期提醒条用——windowDays 个自然日内到期的未使用券，按到期时间升序 */
    List<UserCoupon> expiringSoon(UUID userId, int windowDays);

    /** 本券在该订单行项目上的抵扣额；不满足门槛或已过期返回 null */
    BigDecimal discountOf(UserCoupon coupon, List<CouponPolicy.Line> lines);

    /** 本券在该订单行项目上的适用基数（分类券只算适用分类的行） */
    BigDecimal baseAmountOf(UserCoupon coupon, List<CouponPolicy.Line> lines);

    /**
     * 下单校验：券属于本人、未使用未过期、满足门槛，返回实际抵扣金额；不通过直接抛业务异常
     */
    BigDecimal requireUsableForOrder(UUID userId, UUID userCouponId, List<CouponPolicy.Line> lines);

    /** 核销（条件更新，返回 0 表示并发下已被用掉） */
    boolean consume(UUID userCouponId, UUID userId, UUID orderId);

    /** E08：核销返回 0 之后追溯具体失败原因，供下单链路提示「券已被使用 / 已过期」 */
    String consumeFailureReason(UUID userCouponId, UUID userId);

    /** 订单取消/退款后把券退回券包，返回是否真的回退过（用于轨迹文案） */
    boolean releaseByOrder(UUID orderId);

    /* ---------- E20 适用范围文案 ---------- */

    /** 列表页一次把分类名与商品数取齐后生成文案，key 为模板 id */
    Map<UUID, String> scopeTexts(List<Coupon> coupons);

    /** key 为持券 id，券包与结算页共用 */
    Map<UUID, String> heldScopeTexts(List<UserCoupon> helds);

    /* ---------- E07 转赠 ---------- */

    /**
     * 发起转赠：条件更新把券锁成转赠中。
     *
     * @param receiver 收礼人的用户名或手机号，留空表示生成凭码通用的转赠码
     */
    UserCoupon startTransfer(UUID userId, UUID userCouponId, String receiver);

    /** 撤销转赠，券回到自己券包 */
    UserCoupon cancelTransfer(UUID userId, UUID userCouponId);

    /**
     * 凭转赠码领取：一条 UPDATE 同时改持有人与状态，两个人同时接受只会有一个人拿到券
     */
    UserCoupon acceptTransfer(UUID userId, String token);

    /** 别人指名赠我的、还没领的券 */
    List<UserCoupon> incomingTransfers(UUID userId);

    /**
     * E09：券与积分的组合建议。积分取实时余额（会话里的快照可能已经被别的单扣掉），
     * 未指定券或指定的券不可用时，以本单最省的一张为基准给建议
     */
    org.liuym.flowerv1springboot.vo.CouponViews.ComboPlan comboPlan(UUID userId, List<CouponPolicy.Line> lines,
                                                                            UUID userCouponId);

    /* ---------- 场景发券（E15 下单返券 / E16 邀请奖励） ---------- */
    /**
     * E15：支付成功后的返券出口，由下单链路调用。同一笔订单重复调用只发一张
     * （user_coupon 上 coupon_id + source_ref 的条件唯一索引兜底）。
     *
     * @return 实际发出的张数
     */
    int rebateAfterPaid(UUID orderId, UUID userId, BigDecimal payAmount);

    /**
     * E16：按指定模板给指定用户发券，sourceRef 用于幂等（传邀请关系 id）。
     * 已发过同样来源的券时返回 null，不重复发。
     */
    UserCoupon grantByScene(UUID userId, UUID couponId, String source, UUID sourceRef);

    /* ---------- 判定辅助（E05） ---------- */

    /** 新客判定：一笔「已支付及之后」的订单都没有 */
    boolean isNewCustomer(UUID userId);
}
