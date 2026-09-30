package org.liuym.flowerv1springboot.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePolicyTest {

    private static final RoutePolicy.Node NODE_KM = new RoutePolicy.Node("origin", "昆明斗南花卉基地", 102.7968, 24.8985);
    private static final RoutePolicy.Node NODE_BJ = new RoutePolicy.Node("hub", "华北分拨中心（北京顺义）", 116.6563, 40.13);

    @Test
    void 球面距离落在真实陆路量级内() {
        // 昆明→北京直线约 2070km，允许 5% 误差
        double km = RoutePolicy.distanceKm(NODE_KM, NODE_BJ);
        assertTrue(km > 1950 && km < 2200, "实际=" + km);
    }

    @Test
    void 同城两点至少给一公里() {
        double km = RoutePolicy.distanceKm(
                new RoutePolicy.Node("dest", "a", 116.4074, 39.9042),
                new RoutePolicy.Node("dest", "b", 116.4075, 39.9043));
        assertEquals(1d, km, 0.0001);
    }

    @Test
    void 线路系数空运最省陆运最绕() {
        assertTrue(RoutePolicy.lineFactor(ShippingPolicy.AIR_COLD.code())
                < RoutePolicy.lineFactor(ShippingPolicy.NEXT_DAY.code()));
        assertEquals(RoutePolicy.lineFactor(ShippingPolicy.NEXT_DAY.code()),
                RoutePolicy.lineFactor("未知方式码"), 0.0001);
    }

    @Test
    void 状态决定已走完几段() {
        assertEquals(0, RoutePolicy.passedSegments("pending", 3));
        assertEquals(1, RoutePolicy.passedSegments("paid", 3));
        assertEquals(2, RoutePolicy.passedSegments("shipped", 3));
        assertEquals(3, RoutePolicy.passedSegments("delivered", 3));
        assertEquals(3, RoutePolicy.passedSegments("completed", 3));
        assertEquals(0, RoutePolicy.passedSegments("cancelled", 3));
        // 越界状态也不会给出超过段数的进度
        assertEquals(1, RoutePolicy.passedSegments("delivered", 1), "进度不会超过实际段数");
    }

    @Test
    void 三段线末端是闪送而自提末端不是() {
        List<RoutePolicy.Node> home = List.of(NODE_KM, NODE_BJ,
                new RoutePolicy.Node("dest", "北京·收花点", 116.37, 39.91));
        List<RoutePolicy.Segment> segments = RoutePolicy.segments(home, ShippingPolicy.NEXT_DAY.code(), "shipped");
        assertEquals(2, segments.size());
        assertEquals(RoutePolicy.MODE_LAND, segments.get(0).mode());
        assertEquals(RoutePolicy.MODE_LAST_MILE, segments.get(1).mode());
        assertTrue(segments.get(0).passed());
        assertTrue(segments.get(1).current());

        List<RoutePolicy.Node> pickup = List.of(NODE_KM, NODE_BJ,
                new RoutePolicy.Node("store", "花语轩·北京朝阳门店", 116.4867, 39.9212));
        List<RoutePolicy.Segment> storeSegments = RoutePolicy.segments(pickup, ShippingPolicy.SELF_PICKUP.code(), "shipped");
        assertEquals(RoutePolicy.MODE_LAND, storeSegments.get(storeSegments.size() - 1).mode(),
                "自提的最后一段是干线送达门店，不该标成同城闪送");
    }

    @Test
    void 空运订单干线标为空运() {
        List<RoutePolicy.Segment> trunkOnly = RoutePolicy.segments(
                List.of(NODE_KM, NODE_BJ), ShippingPolicy.AIR_COLD.code(), "paid");
        assertEquals(RoutePolicy.MODE_AIR, trunkOnly.get(0).mode(), "产地到分拨中心是空运干线");
        List<RoutePolicy.Node> airHome = List.of(NODE_KM, NODE_BJ,
                new RoutePolicy.Node("dest", "北京·收花点", 116.37, 39.91));
        List<RoutePolicy.Segment> segments = RoutePolicy.segments(airHome, ShippingPolicy.AIR_COLD.code(), "shipped");
        assertEquals(RoutePolicy.MODE_AIR, segments.get(0).mode());
        assertEquals("空运冷链", RoutePolicy.modeLabel(segments.get(0).mode()));
        assertEquals("海运保鲜", RoutePolicy.modeLabel(RoutePolicy.MODE_SEA));
    }

    @Test
    void 点不够两点时没有线段() {
        assertTrue(RoutePolicy.segments(List.of(NODE_KM), "next_day", "paid").isEmpty());
        assertTrue(RoutePolicy.segments(null, "next_day", "paid").isEmpty());
        assertEquals(0, RoutePolicy.totalKm(List.of(NODE_KM), "next_day"));
    }

    @Test
    void 全程里程大于直线和() {
        List<RoutePolicy.Node> points = List.of(NODE_KM, NODE_BJ);
        long total = RoutePolicy.totalKm(points, ShippingPolicy.NEXT_DAY.code());
        assertTrue(total > RoutePolicy.distanceKm(NODE_KM, NODE_BJ));
        assertFalse(total > RoutePolicy.distanceKm(NODE_KM, NODE_BJ) * 2);
    }

    @Test
    void 分拨中心按最近的一个选() {
        RoutePolicy.Node shanghaiHub = new RoutePolicy.Node("hub", "华东分拨中心（上海虹桥）", 121.361, 31.19);
        RoutePolicy.Node dest = new RoutePolicy.Node("dest", "上海·收花点", 121.4737, 31.2304);
        assertEquals(shanghaiHub, RoutePolicy.nearest(dest, List.of(NODE_BJ, shanghaiHub)));
        assertEquals(NODE_BJ, RoutePolicy.nearest(dest, List.of(NODE_BJ)));
        assertNull(RoutePolicy.nearest(null, List.of(NODE_BJ)));
    }
}
