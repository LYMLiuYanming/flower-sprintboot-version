package org.liuym.flowerv1springboot.map;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.common.AmapClient;
import org.liuym.flowerv1springboot.common.CityGeo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部依赖体检与缓存口径（I16 + I09）。
 *
 * <p>密钥没配 / 配额耗尽 / 引擎偶发报错是外部地图服务的常态，页面要能解释「为什么这里没数据」，
 * 所以缓存命中率与失败原因这些量必须先在纯函数层算对，而不是等到浏览器里看到一片空白才发现算错了。
 */
class AmapDiagnosticsTest {

    /** 没有密钥的客户端：能力全关，但体检接口本身要给出可读结果而不是抛异常 */
    private static AmapClient unconfigured() {
        return new AmapClient("", 10, 12);
    }

    @Test
    void 命中率为零请求时不给出NaN() {
        assertEquals(0, AmapClient.hitRate(0, 0), "一次请求都没有，命中率是 0 而不是 NaN");
        assertEquals(100, AmapClient.hitRate(7, 0));
        assertEquals(0, AmapClient.hitRate(0, 4), "全部回源说明缓存没起作用");
        assertEquals(66.7, AmapClient.hitRate(2, 1), "保留一位小数，页面直接拼百分号");
        assertEquals(50, AmapClient.hitRate(1, 1));
    }

    @Test
    void 未配密钥时能力关闭且计数为零() {
        AmapClient client = unconfigured();
        assertFalse(client.available());
        AmapClient.Stats stats = client.stats();
        assertFalse(stats.configured());
        assertEquals(0, stats.lookups());
        assertEquals(0, stats.hitRate());
        assertTrue(client.lastIssue().isEmpty(), "没配密钥不算一次失败，不该给运营报故障");
        assertTrue(client.geocode("北京市朝阳区").isEmpty());
        assertTrue(client.forecast("110000").isEmpty());
        assertTrue(client.path(AmapClient.PATH_DRIVING, 116.4, 39.9, 121.47, 31.23).isEmpty());
    }

    @Test
    void 缓存有效期下限为一天防止误配成零() {
        AmapClient tiny = new AmapClient("", 0, 0);
        assertEquals(1, tiny.geoCacheHours());
        assertEquals(1, tiny.weatherCacheMinutes());
        assertTrue(unconfigured().geoCacheHours() >= 1);
    }

    @Test
    void 体检记录把四类计数汇总成一个命中率() {
        AmapClient.Stats stats = new AmapClient.Stats(true, 8, 2, 6, 4, 3, 1, 0, 0, 5, "");
        assertEquals(24, stats.lookups());
        assertTrue(stats.geoHitRate() > stats.liveHitRate(), "取点缓存比天气缓存更有效是预期内的量级关系");
        assertEquals(0, stats.pathHitRate(), "一次路径都没查过时不能报出 100%");
        assertTrue(stats.hitRate() > 0 && stats.hitRate() < 100);
    }

    @Test
    void 产地城市能被地址兜底命中() {
        // 伊犁是郁金香基地：地理编码报 ENGINE_RESPONSE_DATA_ERROR 时，产地节点与销量下钻都要靠这条兜底
        assertEquals("伊犁", CityGeo.fromAddress("新疆维吾尔自治区伊犁哈萨克自治州伊宁市解放路 1 号")
                .map(CityGeo.Center::name).orElse(""));
        assertEquals("新疆", CityGeo.provinceOf(CityGeo.byCity("伊犁").map(CityGeo.Center::adcode).orElse("")));
        assertFalse(CityGeo.all().isEmpty());
    }
}
