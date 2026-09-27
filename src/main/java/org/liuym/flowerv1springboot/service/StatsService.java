package org.liuym.flowerv1springboot.service;

import java.util.List;
import java.util.Map;

public interface StatsService {

    /** 看板统计卡：全部走数据库聚合，不在内存里遍历大表 */
    Map<String, Object> overview();

    /** 近 N 天销售趋势（金额/单量），缺失日期补 0，前端 ECharts 直接使用 */
    List<Map<String, Object>> salesTrend(int days);

    /** 热销商品 TopN */
    List<Map<String, Object>> topProducts(int limit);

    /** 消费额 TopN 用户 */
    List<Map<String, Object>> topSpenders(int limit);

    /** 清空看板缓存：看板「刷新」按钮显式调用，避免 60s TTL 内读到旧值 */
    void clearCache();
}
