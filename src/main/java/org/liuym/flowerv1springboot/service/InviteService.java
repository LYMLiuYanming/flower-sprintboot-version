package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.model.InviteCode;
import org.liuym.flowerv1springboot.model.InviteRelation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 邀请有礼（E16）
 */
public interface InviteService {

    /** 我的邀请码，没有就即时生成（并发点两次由 user_id 的条件唯一索引兜住） */
    InviteCode myCode(UUID userId);

    /** 按码查邀请模板：注册页回填与后台校验共用 */
    Optional<InviteCode> findByCode(String code);

    /**
     * 绑定邀请关系：注册成功后调用。一个被邀请人只能绑定一次（invitee_id 唯一索引），
     * 自己邀自己、码不存在、老账号绑定都会明确失败。
     *
     * @return 可直接展示的结果文案
     */
    String bind(String code, UUID inviteeId);

    /** 我邀请过的人 */
    List<InviteRelation> invitedBy(UUID inviterId);

    /**
     * E16 首单奖励：被邀请人首笔支付成功后由下单链路调用，重复调用只会发一次奖。
     *
     * @return 奖励发放文案，null 表示这单没触发奖励（不是被邀请人 / 已发过）
     */
    String grantFirstOrderReward(UUID inviteeId, UUID orderId, BigDecimal payAmount);

    long countInvited(UUID inviterId);

    long countRewarded(UUID inviterId);

    /** 记录列表里要显示邀请人姓名，取不到就退回「花友」 */
    String displayName(UUID userId);
}
