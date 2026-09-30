package org.liuym.flowerv1springboot.map;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.common.CityGeo;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取点兜底字典（I16 + I05）：地理编码引擎偶发 ENGINE_RESPONSE_DATA_ERROR，
 * 城市质心与 adcode→省份这一层就是地图能不能出图、销量能不能按省聚合的最后防线。
 */
class MapGeocodeFallbackTest {

    @Test
    void adcode前两位能定省份() {
        assertEquals("云南", CityGeo.provinceOf("530114"));
        assertEquals("北京", CityGeo.provinceOf("110113"));
        assertEquals("新疆", CityGeo.provinceOf("654000"));
        assertEquals("广东", CityGeo.provinceOf("440103"));
        // 数据残缺时也要归到一个组里，不能让订单从省份统计里悄悄消失
        assertEquals("其他", CityGeo.provinceOf(null));
        assertEquals("其他", CityGeo.provinceOf(""));
        assertEquals("其他", CityGeo.provinceOf("990100"));
        assertEquals("其他", CityGeo.provinceOf("5"), "长度不足两位也按其他处理");
        assertTrue(CityGeo.provinces().size() >= 31);
    }

    @Test
    void 按adcode反查城市质心() {
        assertEquals("昆明", CityGeo.byAdcode("530100").map(CityGeo.Center::name).orElse(""));
        assertEquals("北京", CityGeo.byAdcode("110000").map(CityGeo.Center::name).orElse(""));
        assertTrue(CityGeo.byAdcode("000000").isEmpty());
        assertTrue(CityGeo.byAdcode(null).isEmpty());
        assertTrue(CityGeo.byAdcode("  ").isEmpty());
        // 呈贡与斗南共用一个 adcode，反查只给第一个，避免结果抖动
        assertEquals("呈贡", CityGeo.byAdcode("530114").map(CityGeo.Center::name).orElse(""));
    }

    @Test
    void 每个质心都能反推出省份() {
        // 字典里任何一条 adcode 都落在国标段内，下钻时不该出现「其他」
        CityGeo.all().forEach(center -> assertTrue(
                !"其他".equals(CityGeo.provinceOf(center.adcode())), center.name() + " 的 adcode " + center.adcode() + " 无省份"));
    }

    @Test
    void 产地城市与门店城市都在字典里() {
        // 地图兜底面板与自提页要按城市定位，这些城市缺一条就会让对应节点画不出来
        List.of("昆明", "广州", "成都", "伊犁", "洛阳", "潍坊", "北京", "上海", "杭州", "西安")
                .forEach(name -> assertTrue(CityGeo.byCity(name).isPresent(), "城市字典缺少 " + name));
    }
}
