package org.liuym.flowerv1springboot.map;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.common.RoutePolicy;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实路径与门店自提的时间/距离口径（I03/I04/I15）。
 *
 * <p>这几条都是页面会直接说出口的结论：「实测路线」「今天 18:30 可取」「离你 3.2 公里」，
 * 一旦算错就是给顾客的错误承诺，所以单独测清楚。
 */
class MapRouteGeometryTest {

    private static final RoutePolicy.Node FROM = new RoutePolicy.Node("origin", "昆明斗南花卉基地", 102.7968, 24.8985);
    private static final RoutePolicy.Node TO = new RoutePolicy.Node("hub", "华北分拨中心（北京顺义）", 116.6563, 40.13);

    @Test
    void 图例与图标按方式定稿() {
        assertEquals("fa-plane", RoutePolicy.modeIcon(RoutePolicy.MODE_AIR));
        assertEquals("fa-ship", RoutePolicy.modeIcon(RoutePolicy.MODE_SEA));
        assertEquals("fa-bicycle", RoutePolicy.modeIcon(RoutePolicy.MODE_LAST_MILE));
        assertEquals("fa-truck", RoutePolicy.modeIcon(RoutePolicy.MODE_LAND));
        assertEquals("#2f6fb0", RoutePolicy.modeColor(RoutePolicy.MODE_AIR));
        // 未知方式给干线默认值，不能返回 null 让前端画出破图
        assertEquals("fa-truck", RoutePolicy.modeIcon("从未见过的方式码"));
    }

    @Test
    void 图例按行程顺序去重() {
        List<RoutePolicy.Node> points = List.of(FROM, TO,
                new RoutePolicy.Node("dest", "北京·收花点", 116.37, 39.91));
        List<RoutePolicy.Segment> segments = RoutePolicy.segments(points, "air_cold", "shipped");
        assertEquals(List.of(RoutePolicy.MODE_AIR, RoutePolicy.MODE_LAST_MILE), RoutePolicy.modes(segments));
        assertTrue(RoutePolicy.modes(List.of()).isEmpty());
        assertTrue(RoutePolicy.modes(null).isEmpty());
    }

    @Test
    void 实测折线端点不贴合就判为脏数据() {
        List<double[]> good = List.of(new double[]{102.7970, 24.8990}, new double[]{110.0, 32.0},
                new double[]{116.6500, 40.1340});
        assertTrue(RoutePolicy.realPathUsable(FROM, TO, good, 15));

        // 首点飘到上海：典型的缓存串路，必须退回估算线
        List<double[]> wrong = List.of(new double[]{121.47, 31.23}, new double[]{116.65, 40.13});
        assertFalse(RoutePolicy.realPathUsable(FROM, TO, wrong, 15));
        assertFalse(RoutePolicy.realPathUsable(FROM, TO, List.of(new double[]{102.8, 24.9}), 15), "单点不算路径");
        assertFalse(RoutePolicy.realPathUsable(null, TO, good, 15));
        assertFalse(RoutePolicy.realPathUsable(FROM, TO, null, 15));
    }

    @Test
    void 路径米数换算公里且不低于一公里() {
        assertEquals(2070, RoutePolicy.metersToKm(2_070_400));
        assertEquals(1, RoutePolicy.metersToKm(300), "300 米也标 1 公里，避免出现 0 km 线段");
        assertEquals(1, RoutePolicy.metersToKm(0));
    }

    @Test
    void 候选门店按直线距离升序() {
        RoutePolicy.Node dest = new RoutePolicy.Node("dest", "上海·收花点", 121.4737, 31.2304);
        List<RoutePolicy.Node> stores = List.of(
                new RoutePolicy.Node("store", "苏州门店", 120.5853, 31.2989),
                new RoutePolicy.Node("store", "上海门店", 121.4373, 31.1856));
        List<RoutePolicy.Ranked> ranked = RoutePolicy.rankByDistance(dest, stores);
        assertEquals("上海门店", ranked.get(0).node().name());
        assertTrue(ranked.get(0).km() < ranked.get(1).km(), "距离升序，服务层再叠加同城优先");
        assertTrue(ranked.get(1).km() > 50, "苏州门店应在百公里量级");
        assertTrue(RoutePolicy.rankByDistance(null, stores).isEmpty());
        assertTrue(RoutePolicy.rankByDistance(dest, null).isEmpty());
    }

    @Test
    void 距离相同按名称保证顺序稳定() {
        RoutePolicy.Node target = new RoutePolicy.Node("dest", "a", 116.4, 39.9);
        RoutePolicy.Node yi = new RoutePolicy.Node("store", "乙店", 116.5, 39.9);
        RoutePolicy.Node jia = new RoutePolicy.Node("store", "甲店", 116.5, 39.9);
        // 要点不是谁该排在前面，而是「同距离时先后唯一」：名称按码序比（乙 U+4E59 先于 甲 U+7532），
        // 且与候选数组的传入顺序无关，否则门店列表刷一次换一次序，顾客会以为推荐在飘
        assertEquals(List.of("乙店", "甲店"), names(RoutePolicy.rankByDistance(target, List.of(yi, jia))));
        assertEquals(List.of("乙店", "甲店"), names(RoutePolicy.rankByDistance(target, List.of(jia, yi))));
    }

    private static List<String> names(List<RoutePolicy.Ranked> ranked) {
        return ranked.stream().map(r -> r.node().name()).toList();
    }

    @Test
    void 营业时间文本解析容错() {
        assertEquals(9 * 60, RoutePolicy.parseWindow("09:00-21:00")[0]);
        assertEquals(21 * 60, RoutePolicy.parseWindow("09:00-21:00")[1]);
        assertNull(RoutePolicy.parseWindow("全年 24 小时作业"), "没有区间就不该硬凑一个营业窗口");
        assertNull(RoutePolicy.parseWindow("21:00-09:00"), "打烊早于开门按无效处理");
        assertNull(RoutePolicy.parseWindow(null));
    }

    @Test
    void 备花时长要贴着营业时间算() {
        LocalDateTime morning = LocalDateTime.of(2026, 9, 28, 9, 30);
        assertEquals("今天 11:30 可取", RoutePolicy.pickupReadyText("09:00-21:00", 120, morning));
        // 开门前下单不能承诺「半小时后到」
        assertEquals("今天 11:00 可取", RoutePolicy.pickupReadyText("09:00-21:00", 120,
                LocalDateTime.of(2026, 9, 28, 7, 0)));
        // 打烊前来不及，明确改口到第二天
        assertTrue(RoutePolicy.pickupReadyText("09:00-21:00", 120, LocalDateTime.of(2026, 9, 28, 20, 30))
                .startsWith("今天已错过取货时段"));
        // 没有营业时间的节点只报备花时长，不编造可取时刻
        assertEquals("备花约 90 分钟", RoutePolicy.pickupReadyText("全年 24 小时作业", 90, morning));
        assertNull(RoutePolicy.pickupReadyText("09:00-21:00", null, morning));
    }
}
