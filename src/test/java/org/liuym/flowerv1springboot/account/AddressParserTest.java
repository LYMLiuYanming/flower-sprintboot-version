package org.liuym.flowerv1springboot.account;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.common.AddressParser;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D10 智能识别单测：快递单式自由文本拆字段。断言均按解析器的贪心扫描口径推演得到。
 */
class AddressParserTest {

    @Test
    void parsesCompactNamePhoneAddress() {
        AddressParser.Parsed p = AddressParser.parse("张三 13800138000 广东省深圳市南山区科技园南路1号3栋501");
        assertEquals("张三", p.receiverName());
        assertEquals("13800138000", p.receiverPhone());
        assertEquals("广东省", p.province());
        assertEquals("深圳市", p.city());
        assertEquals("南山区", p.district());
        assertEquals("科技园南路1号3栋501", p.detail());
        assertEquals(6, p.filledCount());
    }

    @Test
    void parsesLabeledFieldsAndMunicipality() {
        AddressParser.Parsed p = AddressParser.parse("收货人：李四，电话15900001111，北京市朝阳区望京街10号");
        assertEquals("李四", p.receiverName());
        assertEquals("15900001111", p.receiverPhone());
        assertEquals("北京市", p.province());
        // 直辖市省即市，市段回填同值，详址从区开始
        assertEquals("北京市", p.city());
        assertEquals("朝阳区", p.district());
        assertEquals("望京街10号", p.detail());
    }

    @Test
    void parsesCityOnlyWhenProvinceOmitted() {
        AddressParser.Parsed p = AddressParser.parse("王五 13712345678 深圳市南山区科技园南路1号");
        assertEquals("王五", p.receiverName());
        assertEquals("13712345678", p.receiverPhone());
        assertNull(p.province());
        assertEquals("深圳市", p.city());
        assertEquals("南山区", p.district());
        assertEquals("科技园南路1号", p.detail());
    }

    @Test
    void toleratesSpacedPhoneNumber() {
        AddressParser.Parsed p = AddressParser.parse("李雷 138 0013 8000 浙江省杭州市西湖区");
        assertEquals("13800138000", p.receiverPhone());
        assertEquals("李雷", p.receiverName());
        assertEquals("浙江省", p.province());
        assertEquals("杭州市", p.city());
        assertEquals("西湖区", p.district());
    }

    @Test
    void parsesAddressWithoutNameOrPhone() {
        AddressParser.Parsed p = AddressParser.parse("四川省成都市武侯区天府大道100号");
        assertNull(p.receiverName());
        assertNull(p.receiverPhone());
        assertEquals("四川省", p.province());
        assertEquals("成都市", p.city());
        assertEquals("武侯区", p.district());
        assertEquals("天府大道100号", p.detail());
        assertEquals(4, p.filledCount());
    }

    @Test
    void blankInputYieldsEmptyResult() {
        AddressParser.Parsed p = AddressParser.parse("   ");
        assertEquals(0, p.filledCount());
        assertNull(AddressParser.parse(null).receiverName());
    }
}
