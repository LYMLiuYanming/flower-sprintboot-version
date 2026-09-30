package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.common.FlowerCarePolicy;
import org.liuym.flowerv1springboot.vo.GeoViews;

import java.util.Optional;

/**
 * 配送城市的实况天气、未来预报与养护建议。查不到一律返回可读的降级状态，页面按「暂无天气」降级，不影响下单
 */
public interface WeatherService {

    /** 按地址文本（或城市名）取该城市天气 + 花束影响建议 */
    Optional<GeoViews.WeatherCard> forAddress(String addressOrCity);

    /** 花田当日天气加成：晴天补光照、降水补水润、极端温度让花苗受压 */
    FlowerCarePolicy.Growth growthFor(String addressOrCity);

    /**
     * 未来预报（I08）：默认给 3 日，逐日带配送结论。
     * 外部接口不可用时 state 为 failed / unconfigured，days 为空列表，绝不抛异常
     */
    GeoViews.ForecastCard forecast(String addressOrCity, int days);

    /**
     * 天气服务可用性（I09）：把「没配密钥 / 引擎报错 / 城市无法识别」区分开写成人话，
     * 让页面的空状态有原因而不是一片空白
     */
    GeoViews.ServiceStatus status(String addressOrCity);

    /**
     * 缓存与兜底的可读状态（I16）：告诉调用方「这次有没有真的打外部接口」「adcode 是从哪来的」。
     *
     * <p>刻意只给一条轻量记录而不是指标体系：页面只需要一句「以下为 10 分钟内的缓存」，
     * 命中与否的计数仍由 {@code AmapClient.stats()} 负责。
     */
    CacheView cacheView(String addressOrCity);

    /**
     * @param resolution 取 adcode 的来源：dict = 命中内置城市字典（不打外部接口）/ geocode = 走地理编码 / name = 原样按城市名查
     * @param live       实况天气缓存态：value / failed / none
     * @param forecast   预报缓存态：value / failed / none
     * @param geo        地理编码缓存态：value / failed / none
     * @param note       给人看的一句话，说明这次是复用缓存还是刚刚回源
     */
    record CacheView(String city, String resolution, String live, String forecast, String geo,
                     Integer cacheMinutes, String note) {

        /** 这次没打任何外部接口：页面据此写「用的是本地缓存」 */
        public boolean servedFromCache() {
            return "value".equals(live) || "value".equals(forecast);
        }
    }
}
