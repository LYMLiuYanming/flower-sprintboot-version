package org.liuym.flowerv1springboot.common;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityGeoTest {

    @Test
    void 带市后缀与裸城市名都能命中() {
        assertEquals("110000", CityGeo.byCity("北京市").map(CityGeo.Center::adcode).orElse(""));
        assertEquals("110000", CityGeo.byCity("北京").map(CityGeo.Center::adcode).orElse(""));
        assertEquals("530100", CityGeo.byCity("昆明").map(CityGeo.Center::adcode).orElse(""));
        assertTrue(CityGeo.byCity(" 上海 ").isPresent());
    }

    @Test
    void 空与未知城市返回空() {
        assertTrue(CityGeo.byCity(null).isEmpty());
        assertTrue(CityGeo.byCity("   ").isEmpty());
        assertTrue(CityGeo.byCity("市").isEmpty());
        assertTrue(CityGeo.byCity("不存在的地方").isEmpty());
        assertTrue(CityGeo.fromAddress(null).isEmpty());
        assertTrue(CityGeo.fromAddress("没有行政地名的门牌 88 号").isEmpty());
    }

    @Test
    void 自由文本地址取最靠前的城市() {
        Optional<CityGeo.Center> hit = CityGeo.fromAddress("云南省昆明市呈贡区斗南花卉大道 1 号");
        assertEquals("昆明", hit.map(CityGeo.Center::name).orElse(""));

        Optional<CityGeo.Center> another = CityGeo.fromAddress("广东省广州市荔湾区芳村大道南 40 号");
        assertEquals("广州", another.map(CityGeo.Center::name).orElse(""));
    }

    @Test
    void 多字城市名不被短名截胡() {
        // 「石家庄市…」里同时含「家庄」候选时，仍应落到石家庄
        Optional<CityGeo.Center> hit = CityGeo.fromAddress("河北省石家庄市桥西区中山西路 10 号");
        assertEquals("石家庄", hit.map(CityGeo.Center::name).orElse(""));
        Optional<CityGeo.Center> harbin = CityGeo.fromAddress("黑龙江省哈尔滨市南岗区");
        assertEquals("哈尔滨", harbin.map(CityGeo.Center::name).orElse(""));
    }

    @Test
    void 坐标落在中国境内() {
        CityGeo.all().forEach(center -> {
            assertTrue(center.lng() > 73 && center.lng() < 136, center.name() + " 经度异常 " + center.lng());
            assertTrue(center.lat() > 18 && center.lat() < 54, center.name() + " 纬度异常 " + center.lat());
        });
    }
}
