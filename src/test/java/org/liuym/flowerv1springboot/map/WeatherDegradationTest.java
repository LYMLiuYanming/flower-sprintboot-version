package org.liuym.flowerv1springboot.map;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.common.AmapClient;
import org.liuym.flowerv1springboot.common.FlowerCarePolicy;
import org.liuym.flowerv1springboot.service.WeatherService;
import org.liuym.flowerv1springboot.service.impl.WeatherServiceImpl;
import org.liuym.flowerv1springboot.vo.GeoViews;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部天气不可用时的可读降级（I09）与缓存/兜底状态（I16）。
 *
 * <p>只测「没配密钥 / 还没查过」这两条纯本地路径：任何会真的打高德的网络调用都不该进单测，
 * 网络抖动不该让构建结果跟着抖。
 */
class WeatherDegradationTest {

    private static final String KEY_PLACEHOLDER = "amap-key-should-never-leak";

    private static WeatherServiceImpl service(String key) {
        return new WeatherServiceImpl(new AmapClient(key, 10, 12), 10);
    }

    @Test
    void 未配密钥时实况与预报都按查不到处理且不抛异常() {
        WeatherServiceImpl svc = service("");

        assertTrue(svc.forAddress("北京市朝阳区").isEmpty());
        assertTrue(svc.forAddress(null).isEmpty());
        assertTrue(svc.forAddress("   ").isEmpty());

        GeoViews.ForecastCard card = svc.forecast("北京市朝阳区", 3);
        assertEquals("unconfigured", card.state());
        assertTrue(card.days().isEmpty());
        // 城市仍然给得出来：预报取不到时按内置字典报城市名，页面不至于连「北京」都显示不出
        assertEquals("北京", card.city());
        assertNotNull(card.note());
        assertTrue(card.note().contains("暂无"));
    }

    @Test
    void 取不到天气时不给假的花田成长加成() {
        FlowerCarePolicy.Growth growth = service("").growthFor("北京市朝阳区");
        assertEquals(0, growth.bonus(), "没有实时天气就不该凭空给光照或水润");
        assertTrue(growth.sun() == 0 && growth.water() == 0 && growth.stress() == 0);
    }

    @Test
    void 状态文案是人话且不带密钥() {
        WeatherServiceImpl svc = service("");

        GeoViews.ServiceStatus status = svc.status("上海市");
        assertEquals(Boolean.FALSE, status.configured());
        assertEquals("unconfigured", status.state());
        assertTrue(status.note().contains("暂无数据") || status.note().contains("不影响下单"));

        WeatherService.CacheView view = svc.cacheView("上海市");
        // 命中内置城市字典就不该走地理编码（I16），省配额且结果稳定
        assertEquals("dict", view.resolution());
        assertEquals("上海", view.city());
        assertEquals("none", view.live());
        assertEquals("none", view.forecast());
        assertEquals("none", view.geo());
        assertFalse(view.servedFromCache());
        assertTrue(view.note().contains("不调用外部接口"));

        String all = status.note() + view.note() + view.city();
        assertFalse(all.contains(KEY_PLACEHOLDER));
    }

    @Test
    void 已配密钥但从未查询时提示会回源一次() {
        WeatherServiceImpl svc = service(KEY_PLACEHOLDER);

        // cacheView 只探问本地缓存，绝不因为「想看状态」去打外部接口
        WeatherService.CacheView view = svc.cacheView("广东省深圳市");
        assertEquals("dict", view.resolution());
        assertEquals("none", view.live());
        assertFalse(view.servedFromCache());
        assertTrue(view.note().contains("首次查询会回源"));
        assertFalse(view.note().contains(KEY_PLACEHOLDER));
        assertEquals(Integer.valueOf(10), view.cacheMinutes());
    }

    @Test
    void 外部异常文本里不能带出密钥() {
        AmapClient client = new AmapClient(KEY_PLACEHOLDER, 10, 12);

        // URI.create / HttpClient 的报错可能整段带上请求 URL，而 URL 的 query 里就有 key=
        String masked = client.scrub("https://restapi.amap.com/v3/weather/weatherInfo?"
                + "city=110000&key=" + KEY_PLACEHOLDER);
        assertFalse(masked.contains(KEY_PLACEHOLDER));
        assertTrue(masked.contains("***"));
        assertEquals("", client.scrub(null));
        // 没配密钥时没有需要遮的东西，原文返回即可，别把日志改成空串让排查抓瞎
        assertEquals("URI: null", new AmapClient("", 10, 12).scrub("URI: null"));
    }

    @Test
    void 空参数不破坏缓存态判定() {
        AmapClient client = new AmapClient("", 10, 12);
        assertFalse(client.available());
        assertTrue(client.liveWeather("110000").isEmpty());
        assertTrue(client.forecast(" ").isEmpty());
        assertTrue(client.geocode(null).isEmpty());
        assertEquals("none", client.liveCacheState("110000"));
        assertEquals("none", client.geoCacheState("北京市朝阳区"));
        assertEquals("none", client.forecastCacheState("110000"));
        assertTrue(client.lastIssue().isEmpty(), "没配密钥不算一次失败，不该留下故障原因");
    }
}
