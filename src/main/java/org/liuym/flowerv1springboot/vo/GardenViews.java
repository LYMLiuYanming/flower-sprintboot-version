package org.liuym.flowerv1springboot.vo;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 我的花田视图：成长值一律下发「回落后实际值 + 成熟阈值 + 百分比」，
 * 页面只负责画，不再自己算阶段与是否成熟
 */
public final class GardenViews {

    private GardenViews() {
    }

    public record SeedCard(String code, String name, String icon, String originName, Integer needGrowth,
                           String rewardText, String note, Boolean plantable) {
    }

    public record Plot(
            UUID id,
            /** 地块序号（I11）：会员可同时在两块地上培育 */
            Integer slotNo,
            String slotName,
            String seedCode,
            String seedName,
            String icon,
            String originName,
            Integer growth,
            Integer needGrowth,
            Integer percent,
            String stage,
            String status,
            Integer waterLeft,
            Integer waterTimes,
            Integer streakDays,
            Long idleDays,
            /** 衰减提醒文案：闲置超过宽限期时才非空 */
            String warning,
            String estimate,
            Boolean mature,
            /** 转赠确认态（I13）：pending 时券还没发出，收礼方确认才到账 */
            String giftState,
            String giftStateLabel,
            String giftMessage,
            String receiverName,
            String sowedAt) {
    }

    /**
     * 一块地的壳子（I11）：locked 的地块前端画灰，并原样显示 lockedNote，
     * 不要让用户以为按钮坏了。
     */
    public record Slot(Integer slotNo, String name, Plot plot, Boolean locked, String lockedNote, Boolean active) {
    }

    /** 今日天气给花田的加成，页面直接展示成「光照 +2 / 水润 +2」 */
    public record WeatherBonus(Integer sun, Integer water, Integer stress, String note, Integer temperature, String weather) {
    }

    /** 成长曲线上的一个点（I14） */
    public record CurvePoint(String at, Integer growth, Integer delta, String stage, String source,
                             String weather, Integer temperature) {
    }

    /** 成长曲线：points 已按时间正序，note 解释曲线的成因（例如「近 3 天没浇水，成长值回落」） */
    public record GrowthCurve(Integer pointCount, Integer growth, Integer needGrowth, List<CurvePoint> points,
                              String note) {
    }

    /**
     * 兑换与转赠流水（I12）：券状态是查询时刻从券包读出来的，不是发券时的快照。
     *
     * <p>couponCode 只在券确实躺在查看者本人券包里时才下发（其余情形由服务端置空），
     * 转赠记录里的券属于收礼方，不应把券码一并显示给送礼人。
     */
    public record ExchangeRow(UUID id, UUID plotId, Integer slotNo, String seedName, String type, String state,
                              String couponName, String couponCode, BigDecimal amount, BigDecimal threshold,
                              Integer validDays,
                              /** unused / used / expired / missing，缺失表示券没落到券包 */
                              String couponState, String couponExpireAt, UUID userCouponId,
                              String receiverName, String message, String at) {
    }

    /** 待确认的转赠（I13）：收礼方视角与送礼方视角共用一个形状 */
    public record GiftRow(UUID exchangeId, UUID plotId, Integer slotNo, String seedName, String icon,
                          String fromName, String toName, String message, String state, String at,
                          Boolean mine) {
    }

    public record Home(Plot plot, List<Slot> slots, Integer slotNo, Boolean vip, Integer slotsAllowed,
                       List<SeedCard> seeds, Integer waterPerDay,
                       GeoViews.WeatherCard weather, WeatherBonus bonus,
                       /** 当日养护建议（I10）：天气影响成长值，也要影响这句话 */
                       String careNote,
                       GrowthCurve curve,
                       List<ExchangeRow> exchanges, List<GiftRow> pendingGifts,
                       Long matured, Long gifted, Long receivedCount, List<Received> received,
                       /** 花田奖励券在本人券包里的张数与其中仍可用的张数（I12）：状态现读自券包 */
                       Integer gardenCoupons, Integer gardenCouponsUsable,
                       /** 今日 + 未来 3 日预报与逐日配送结论（I08） */
                       Forecast forecast,
                       /** 天气可用性与缓存态（I09/I16）：取不到实时天气时页面显示它的 note，而不是空白 */
                       WeatherState weatherState) {
    }

    /**
     * 预报里的一天（I08）。建议文案全部来自 {@code FlowerCarePolicy.advise}，
     * label / today 由服务端按日期比对算好，页面不再自己判断「哪一行是今天」。
     */
    public record ForecastDayRow(String label, String date, String weather, String nightWeather,
                                 Integer dayTemp, Integer nightTemp, String level, String headline,
                                 String deliveryNote, Boolean coldChain, Boolean today) {
    }

    /** 今日 + 未来 3 日预报卡片（I08）：state 非 ok 时 days 为空列表，note 是给人看的降级说明 */
    public record Forecast(String city, String reportTime, String state, String note, List<ForecastDayRow> days) {

        public boolean degraded() {
            return !"ok".equals(state);
        }
    }

    /**
     * 天气服务的可读状态（I09/I16）。
     *
     * @param degraded   true 表示这次没拿到实时天气，页面要按 note 走降级文案而不是留空
     * @param resolution adcode 来源：dict 内置城市字典命中 / geocode 地理编码命中 / name 原样按城市名查
     * @param cached     true 表示本次复用本地缓存，没有再打外部接口
     */
    public record WeatherState(String city, String state, String note, Boolean degraded,
                               Integer cacheMinutes, String resolution, Boolean cached) {
    }

    /** 收到的转赠（含自己养成熟留着的） */
    public record Received(String seedName, String fromUsername, String message, String at, String couponName) {
    }

    /** 一次操作（开坑/浇水）后的回执 */
    public record Action(String msg, Plot plot, WeatherBonus bonus) {
    }

    public record Reward(String msg, String couponName, BigDecimal amount, BigDecimal threshold, Integer validDays,
                         /** 兑换流水 id（I12），前端可用它在记录里定位这一笔 */
                         UUID exchangeId) {
    }
}
