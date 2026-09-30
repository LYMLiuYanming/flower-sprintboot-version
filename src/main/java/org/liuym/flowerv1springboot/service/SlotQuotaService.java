package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.vo.ShippingViews;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 预约时段运力：把「某店某小时能送几单」变成可查询、可抢占的配额，
 * 避免母亲节这类高峰把超出履约能力的订单接进来。
 */
public interface SlotQuotaService {

    /** 未来 days 天 × 可约整点的运力日历，页面据此置灰已满时段 */
    List<ShippingViews.SlotView> calendar(LocalDateTime now, int days);

    /** 抢占一个名额，约满抛 BusinessException；必须与下单在同一事务内，回滚即释放 */
    void occupy(LocalDateTime slot);

    /** 关单（取消/退款）时归还名额；非预约单传 null 直接跳过 */
    void release(LocalDateTime slot);
}
