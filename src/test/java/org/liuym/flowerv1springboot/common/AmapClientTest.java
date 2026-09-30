package org.liuym.flowerv1springboot.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 高德响应解析：重点覆盖它的两处怪形状——「空值返回空数组而不是 null」与「status 是字符串 1」，
 * 以及密钥未配置时必须整体静默关闭，不能让地图类功能把主链路带崩
 */
class AmapClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 空数组形状按缺失处理() {
        JsonNode node = read("{\"city\":[],\"district\":[],\"province\":\"北京市\",\"adcode\":\"110105\"}");
        assertEquals(null, AmapClient.text(node, "city"));
        assertEquals(null, AmapClient.text(node, "district"));
        assertEquals("北京市", AmapClient.text(node, "province"));
        assertEquals("110105", AmapClient.text(node, "adcode"));
        assertEquals(null, AmapClient.text(node, "不存在"));
        assertEquals(null, AmapClient.text(null, "任意"));
    }

    @Test
    void 数值字段容错解析() {
        JsonNode node = read("{\"temperature\":\"20\",\"humidity\":\"40\",\"windpower\":\"≤3\",\"daytemp\":\"null\"}");
        assertEquals(20, AmapClient.integer(node, "temperature"));
        assertEquals(40, AmapClient.integer(node, "humidity"));
        assertEquals(null, AmapClient.integer(node, "windpower"), "非数字风力不该抛错");
        assertEquals(null, AmapClient.integer(node, "daytemp"));
        assertEquals(null, AmapClient.decimal(node, "windpower"));
        JsonNode floats = read("{\"lng\":\"116.4074\"}");
        assertEquals(116.4074d, AmapClient.decimal(floats, "lng"), 0.000001);
    }

    @Test
    void 地理编码结果拆成经纬度() {
        JsonNode body = read("{\"status\":\"1\",\"geocodes\":[{\"formatted_address\":\"云南省昆明市\","
                + "\"province\":\"云南省\",\"city\":\"昆明市\",\"district\":[],\"adcode\":\"530100\","
                + "\"location\":\"102.8329,24.8801\"}]}");
        AmapClient.Point point = AmapClient.first(body, "geocodes").map(AmapClient::toPoint).orElseThrow();
        assertEquals(102.8329d, point.lng(), 0.000001);
        assertEquals(24.8801d, point.lat(), 0.000001);
        assertEquals("昆明市", point.city());
        assertEquals("530100", point.adcode());
        assertTrue(point.located());
    }

    @Test
    void 空结果与错误结果都返回空() {
        assertTrue(AmapClient.first(read("{\"geocodes\":[]}"), "geocodes").isEmpty());
        assertTrue(AmapClient.first(read("{\"status\":\"0\",\"info\":\"ENGINE_RESPONSE_DATA_ERROR\"}"), "geocodes").isEmpty());
        assertTrue(AmapClient.first(null, "geocodes").isEmpty());
        assertFalse(AmapClient.first(read("{\"geocodes\":[]}"), "geocodes").isPresent());
    }

    @Test
    void 缺location时不假装已定位() {
        AmapClient.Point point = AmapClient.toPoint(read("{\"city\":\"北京市\",\"location\":\"\"}"));
        assertEquals(null, point.lng());
        assertFalse(point.located());
    }

    @Test
    void 预报casts逐日展开() {
        JsonNode node = read("{\"city\":\"北京市\",\"adcode\":\"110000\",\"reporttime\":\"2026-09-28 00:04:19\","
                + "\"casts\":[{\"date\":\"2026-09-28\",\"dayweather\":\"多云\",\"nightweather\":\"阴\","
                + "\"daytemp\":\"26\",\"nighttemp\":\"16\"},{\"date\":\"2026-09-29\",\"dayweather\":\"小雨\","
                + "\"nightweather\":\"小雨\",\"daytemp\":\"22\",\"nighttemp\":\"15\"}]}");
        AmapClient.Forecast forecast = AmapClient.Forecast.of(node);
        assertEquals("北京市", forecast.city());
        assertEquals(2, forecast.casts().size());
        assertEquals("小雨", forecast.casts().get(1).dayWeather());
        assertEquals(26, forecast.casts().get(0).dayTemp());
    }

    @Test
    void 实况天气解析保留养护判断需要的字段() {
        JsonNode live = read("{\"city\":\"北京市\",\"adcode\":\"110000\",\"weather\":\"阴\",\"temperature\":\"18\","
                + "\"humidity\":\"53\",\"winddirection\":\"东\",\"windpower\":\"≤3\",\"reporttime\":\"2026-09-28 00:04:19\"}");
        AmapClient.Live parsed = AmapClient.toLive(live);
        assertEquals("阴", parsed.weather());
        assertEquals(18, parsed.temperature());
        assertEquals(53, parsed.humidity());
        assertEquals("东", parsed.windDirection());
    }

    @Test
    void 未配置密钥时所有能力静默关闭() {
        AmapClient client = new AmapClient("", 10, 12);
        assertFalse(client.available());
        assertEquals(Optional.empty(), client.geocode("北京市朝阳区"));
        assertEquals(Optional.empty(), client.liveWeather("110000"));
        assertTrue(client.forecast("110000").isEmpty());
    }

    @Test
    void 查询参数做URL编码() {
        assertEquals("%E5%8C%97%E4%BA%AC", AmapClient.encode("北京"));
        assertEquals("", AmapClient.encode(null));
    }
}
