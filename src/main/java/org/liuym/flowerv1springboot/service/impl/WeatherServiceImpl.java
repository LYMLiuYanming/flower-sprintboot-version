package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.AmapClient;
import org.liuym.flowerv1springboot.common.CityGeo;
import org.liuym.flowerv1springboot.common.FlowerCarePolicy;
import org.liuym.flowerv1springboot.service.WeatherService;
import org.liuym.flowerv1springboot.vo.GeoViews;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 天气出口：城市名或地址文本都能问，内部先翻译成 adcode 再走高德（结果由 AmapClient 缓存）。
 * 没配密钥或外部接口失败时返回带原因的空结果，调用方负责降级展示，绝不让 500 冒到页面上。
 */
@Service
public class WeatherServiceImpl implements WeatherService {

    /** 预报默认给 3 日（I08）：再长高德也只给 3 或 7 天，而配送决策只关心「明天要不要改时段」 */
    private static final int DEFAULT_FORECAST_DAYS = 3;
    private static final int MAX_FORECAST_DAYS = 7;

    private final AmapClient amapClient;
    private final int weatherCacheMinutes;

    public WeatherServiceImpl(AmapClient amapClient,
                              @Value("${amap.weather-cache-minutes:10}") int weatherCacheMinutes) {
        this.amapClient = amapClient;
        this.weatherCacheMinutes = Math.max(1, weatherCacheMinutes);
    }

    @Override
    public Optional<GeoViews.WeatherCard> forAddress(String addressOrCity) {
        return live(addressOrCity).map(live -> {
            FlowerCarePolicy.Advice advice = FlowerCarePolicy.advise(
                    live.temperature(), live.humidity(), live.weather(), live.windPower());
            return new GeoViews.WeatherCard(live.city(), live.adcode(), live.weather(), live.temperature(),
                    live.humidity(), live.windDirection(), live.windPower(), live.reportTime(),
                    advice.level(), advice.headline(), advice.tips(), advice.recommendColdChain(),
                    // 养护文案（I10）随天气变：同一条提示对高温天和雨天该说不同的话
                    FlowerCarePolicy.careNote(live.temperature(), live.humidity(), live.weather(), live.windPower()));
        });
    }

    @Override
    public FlowerCarePolicy.Growth growthFor(String addressOrCity) {
        AmapClient.Live live = live(addressOrCity).orElse(null);
        return FlowerCarePolicy.growth(live == null ? null : live.weather(), live == null ? null : live.temperature());
    }

    @Override
    public GeoViews.ForecastCard forecast(String addressOrCity, int days) {
        int limit = Math.min(MAX_FORECAST_DAYS, Math.max(1, days == 0 ? DEFAULT_FORECAST_DAYS : days));
        String text = addressOrCity == null ? "" : addressOrCity.trim();
        String adcode = adcodeOf(text);
        List<AmapClient.Forecast> forecasts = adcode.isEmpty()
                ? amapClient.forecast(text) : amapClient.forecast(adcode);
        if (forecasts.isEmpty() && !adcode.isEmpty()) {
            forecasts = amapClient.forecast(text);
        }
        List<AmapClient.Forecast.Cast> casts = forecasts.isEmpty()
                ? List.of() : forecasts.get(0).casts();
        List<AmapClient.Forecast.Cast> limited = new ArrayList<>(casts.subList(0, Math.min(limit, casts.size())));
        List<FlowerCarePolicy.ForecastDay> advice = FlowerCarePolicy.forecastAdvice(limited);
        String city = forecasts.isEmpty() ? CityGeo.fromAddress(text).map(CityGeo.Center::name).orElse(text)
                : forecasts.get(0).city();
        String state = !amapClient.available() ? "unconfigured" : (advice.isEmpty() ? "failed" : "ok");
        return new GeoViews.ForecastCard(city, adcode.isEmpty() ? null : adcode,
                forecasts.isEmpty() ? null : forecasts.get(0).reportTime(), advice, state, forecastNote(state));
    }

    @Override
    public GeoViews.ServiceStatus status(String addressOrCity) {
        String text = addressOrCity == null ? "" : addressOrCity.trim();
        if (!amapClient.available()) {
            return new GeoViews.ServiceStatus(false, false, "unconfigured",
                    "未配置高德服务端密钥，天气与预报按「暂无数据」展示，不影响下单", weatherCacheMinutes, null,
                    CityGeo.fromAddress(text).map(CityGeo.Center::name).orElse(text));
        }
        boolean hasLive = live(text).isPresent();
        String issue = amapClient.lastIssue().orElse(null);
        if (hasLive) {
            return new GeoViews.ServiceStatus(true, true, "ok",
                    "天气数据约 " + weatherCacheMinutes + " 分钟刷新一次，同一城市不会每请求都打外部接口",
                    weatherCacheMinutes, issue, CityGeo.fromAddress(text).map(CityGeo.Center::name).orElse(text));
        }
        // 查不到不等于没配密钥：把两种原因分开写，运营看到 unconfigured 与 failed 就知道该补密钥还是该等接口恢复
        return new GeoViews.ServiceStatus(true, false, text.isEmpty() ? "unknown_city" : "failed",
                "暂时取不到实时天气，已按城市气候常态给出建议"
                        + (issue == null ? "" : "；最近一次失败原因：" + issue),
                weatherCacheMinutes, issue,
                CityGeo.fromAddress(text).map(CityGeo.Center::name).orElse(text));
    }

    /**
     * 缓存与兜底的可读状态（I16）。这里只探问本地状态，绝不触发外部调用：
     * 一个「查看状态」的动作如果反过来把配额打掉了，状态本身就失去了意义。
     */
    @Override
    public CacheView cacheView(String addressOrCity) {
        String text = addressOrCity == null ? "" : addressOrCity.trim();
        String dictAdcode = CityGeo.fromAddress(text).map(CityGeo.Center::adcode).orElse("");
        String city = CityGeo.fromAddress(text).map(CityGeo.Center::name).orElse(text);
        String queryKey = dictAdcode.isEmpty() ? text : dictAdcode;
        String geo = amapClient.geoCacheState(text);
        String resolution = !dictAdcode.isEmpty() ? "dict" : ("value".equals(geo) ? "geocode" : "name");
        String live = amapClient.liveCacheState(queryKey);
        if ("none".equals(live) && !dictAdcode.isEmpty()) {
            // 实况既可能按 adcode 查过，也可能按城市名查过（老调用路径），两个键都探一下才不误报「没查过」
            live = amapClient.liveCacheState(text);
        }
        String forecast = amapClient.forecastCacheState(queryKey);
        return new CacheView(city, resolution, live, forecast, geo, weatherCacheMinutes,
                cacheNote(live, forecast));
    }

    /** 把缓存态翻译成一句人话，页面原样显示，不在前端再拼一遍规则 */
    private String cacheNote(String live, String forecast) {
        if (!amapClient.available()) {
            return "未配置服务端密钥，天气按内置城市气候常态给出，全程不调用外部接口";
        }
        if ("value".equals(live) || "value".equals(forecast)) {
            return "本次复用本地缓存（约 " + weatherCacheMinutes + " 分钟内有效），没有再次请求外部接口";
        }
        if ("failed".equals(live) || "failed".equals(forecast)) {
            return "刚刚试过外部接口没有成功，" + weatherCacheMinutes + " 分钟内不再重复请求，先按城市气候常态给建议";
        }
        return "首次查询会回源一次，之后 " + weatherCacheMinutes + " 分钟内同城复用缓存";
    }

    private static String forecastNote(String state) {
        return switch (state) {
            case "unconfigured" -> "暂无未来几天的预报，按当前实况天气安排配送即可";
            case "failed" -> "暂时取不到未来几天的预报，稍后会自动重试；当前仍可按实时天气安排配送";
            default -> null;
        };
    }

    private Optional<AmapClient.Live> live(String addressOrCity) {
        if (addressOrCity == null || addressOrCity.isBlank()) {
            return Optional.empty();
        }
        String text = addressOrCity.trim();
        String adcode = adcodeOf(text);
        // adcode 优先：城市名有同名歧义时（如各级「吉林」）按编码查更稳
        Optional<AmapClient.Live> live = amapClient.liveWeather(adcode.isEmpty() ? text : adcode);
        return live.isPresent() ? live : amapClient.liveWeather(text);
    }

    /** 地址 → adcode：内置字典命中就不打外部接口（省配额），命中不了再让高德兜 */
    private String adcodeOf(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return CityGeo.fromAddress(text).map(CityGeo.Center::adcode)
                .orElseGet(() -> amapClient.geocode(text).map(AmapClient.Point::adcode).orElse(""));
    }
}
