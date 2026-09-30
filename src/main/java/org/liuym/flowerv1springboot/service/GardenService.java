package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.vo.GardenViews;

import java.util.List;
import java.util.UUID;

/**
 * 我的花田：开坑、浇水、成熟兑换与转赠。成长值规则全部走 GardenPolicy，本接口只管编排与落库。
 *
 * <p>写方法都按「用户 + 地块序号」定位（I11 多块地），并且内部一律用条件 UPDATE + 影响行数判定，
 * 重复提交与并发点击不会双发成长值或双发券。
 */
public interface GardenService {

    /**
     * 花田总览。
     *
     * @param slotNo 前端当前聚焦的地块，越界时服务端自动收回可见范围
     */
    GardenViews.Home home(UUID userId, int slotNo);

    /** 在指定地块种下花苗；地块未解锁（非会员开第 2 块）会抛业务异常 */
    GardenViews.Action plant(UUID userId, String seedCode, int slotNo);

    /** 浇一次水：受每日次数上限约束，成长值叠加当日天气与连续培育加成 */
    GardenViews.Action water(UUID userId, int slotNo);

    /** 成熟后自留，兑换成一张定向优惠券，同时留下兑换流水（I12） */
    GardenViews.Reward redeem(UUID userId, int slotNo);

    /** 成熟后送给好友：先记为待确认（I13），对方确认时才发券 */
    GardenViews.Reward gift(UUID userId, int slotNo, String username, String message);

    /** 收礼方确认收下：发券到本人券包并落兑换流水 */
    GardenViews.Reward acceptGift(UUID userId, UUID exchangeId);

    /** 收礼方婉拒：花退回送礼人，仍可继续兑换 */
    GardenViews.Action declineGift(UUID userId, UUID exchangeId);

    /** 送礼人撤回待确认的转赠 */
    GardenViews.Action cancelGift(UUID userId, UUID exchangeId);

    /** 成长曲线（I14）：按地块读历史流水 */
    GardenViews.GrowthCurve curve(UUID userId, int slotNo);

    /** 兑换与转赠记录（I12）：券的当前状态现读自券包 */
    List<GardenViews.ExchangeRow> exchanges(UUID userId);

    /** 待我确认的转赠（I13） */
    List<GardenViews.GiftRow> pendingGifts(UUID userId);
}
