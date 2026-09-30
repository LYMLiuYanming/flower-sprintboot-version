package org.liuym.flowerv1springboot.common;

import java.util.ArrayList;
import java.util.List;

/**
 * 天气对鲜花的影响口径：把「温度 + 湿度 + 天况 + 风力」翻译成顾客看得懂的一句话，
 * 以及门店该不该提示改走冷链/保温。
 *
 * <p>纯函数、不查接口，便于把每条阈值单独测掉；外部天气数据由 WeatherService 取得后传进来。
 */
public final class FlowerCarePolicy {

    private FlowerCarePolicy() {
    }

    public static final String LEVEL_GOOD = "good";
    public static final String LEVEL_CAUTION = "caution";
    public static final String LEVEL_RISK = "risk";

    /** 高温阈值：超过后切花脱水与细菌繁殖同时加速 */
    public static final int HOT_TEMP = 32;
    /** 偏高阈值：仍需提醒，但不改变配送建议 */
    public static final int WARM_TEMP = 28;
    /** 低温阈值：南方湿冷不明显，北方会冻伤花瓣 */
    public static final int COLD_TEMP = 3;
    /** 干燥阈值：相对湿度低于此值瓶插期缩短 */
    public static final int DRY_HUMIDITY = 35;

    public record Advice(String level, String headline, List<String> tips, boolean recommendColdChain) {
    }

    /**
     * @param weather 高德天况文本，如「晴」「多云」「阵雨」「雷阵雨」
     * @param windPower 风力文本，如「≤3」「4-5」「6-7」
     */
    public static Advice advise(Integer temperature, Integer humidity, String weather, String windPower) {
        List<String> tips = new ArrayList<>();
        String level = LEVEL_GOOD;
        boolean coldChain = false;
        StringBuilder headline = new StringBuilder();

        if (temperature != null && temperature >= HOT_TEMP) {
            level = LEVEL_RISK;
            coldChain = true;
            headline.append("高温 ").append(temperature).append("℃");
            tips.add("花材离水后在高温下 1 小时左右就会明显失水，已为您优先安排冰袋与保温箱");
            tips.add("建议改选「空运冷链」或把送达时间调到 18:00 之后");
        } else if (temperature != null && temperature >= WARM_TEMP) {
            level = LEVEL_CAUTION;
            headline.append("偏热 ").append(temperature).append("℃");
            tips.add("收到后请剪根 2-3 厘米并深水醒花 1 小时再插瓶");
        } else if (temperature != null && temperature <= COLD_TEMP) {
            level = LEVEL_RISK;
            headline.append("低温 ").append(temperature).append("℃");
            tips.add("花瓣遇冷会水渍化发暗，请安排保温包装并提醒收花人及时拆封");
        }

        if (isRainy(weather)) {
            if (LEVEL_GOOD.equals(level)) {
                level = LEVEL_CAUTION;
            }
            if (headline.length() > 0) {
                headline.append(" · ");
            }
            headline.append(weather);
            tips.add("雨天配送可能迟到 10-20 分钟，建议确保收花人当天可收货或改选无接触放置");
        }

        if (humidity != null && humidity <= DRY_HUMIDITY) {
            tips.add("空气干燥，请每 2 天换水并避开空调出风口");
        }
        if (windLevel(windPower) >= 5) {
            tips.add("风力较大，户外摆放请注意花束被吹倒");
        }
        if (tips.isEmpty()) {
            tips.add("当前天气适宜配送，正常养护即可");
        }
        if (headline.length() == 0) {
            headline.append(weather == null || weather.isBlank() ? "天气适宜" : weather);
        }
        return new Advice(level, headline.toString(), List.copyOf(tips), coldChain);
    }

    /** 天况文本里出现这些字样就按降雨/降雪处理（高德会给「阵雨」「雷阵雨」「中雨」等） */
    public static boolean isRainy(String weather) {
        if (weather == null) {
            return false;
        }
        return weather.contains("雨") || weather.contains("雪") || weather.contains("冰雹");
    }

    /**
     * 未来几日的配送结论（I08）：把高德预报的逐日天况与温差翻译成「那天要不要改时段」。
     *
     * <p>只用预报里真实存在的字段（天况、昼温、夜温），不编造降水概率；
     * 昼夜温差之所以单独判，是因为切花在温差大的日子运输箱内会结露，花瓣水渍化就是这么来的。
     */
    public record ForecastDay(String date, String dayWeather, String nightWeather, Integer dayTemp, Integer nightTemp,
                              String level, String headline, String deliveryNote, boolean recommendColdChain) {
    }

    /** 昼夜温差达到这个值就开始提醒醒花与结露 */
    public static final int WIDE_DIURNEAL = 12;

    public static List<ForecastDay> forecastAdvice(List<AmapClient.Forecast.Cast> casts) {
        List<ForecastDay> days = new ArrayList<>();
        if (casts == null) {
            return days;
        }
        for (AmapClient.Forecast.Cast cast : casts) {
            if (cast == null) {
                continue;
            }
            Advice advice = advise(cast.dayTemp(), null, cast.dayWeather(), null);
            String note = deliveryNote(cast.dayTemp(), cast.nightTemp(), cast.dayWeather(), advice);
            days.add(new ForecastDay(cast.date(), cast.dayWeather(), cast.nightWeather(), cast.dayTemp(),
                    cast.nightTemp(), advice.level(), advice.headline(), note, advice.recommendColdChain()));
        }
        return days;
    }

    /** 单日配送建议文本：把 advise 的分档结论收敛成一句可执行的话 */
    public static String deliveryNote(Integer dayTemp, Integer nightTemp, String weather, Advice advice) {
        if (advice != null && advice.recommendColdChain()) {
            return "当日高温，建议改选空运冷链或把送达调到 18:00 之后";
        }
        if (dayTemp != null && dayTemp <= COLD_TEMP) {
            return "当日低温，需保温包装并提醒收花人当天拆封";
        }
        if (isRainy(weather)) {
            return "当日有降水，配送可能迟到，建议选无接触放置或留电话确认";
        }
        if (dayTemp != null && nightTemp != null && dayTemp - nightTemp >= WIDE_DIURNEAL) {
            return "昼夜温差较大，运输箱内易结露，收到后请先深水醒花再插瓶";
        }
        if (dayTemp != null && dayTemp >= WARM_TEMP) {
            return "当日偏热，宜上午或傍晚送达，花材到手后尽快入水";
        }
        return "当日适宜配送，按预约时段正常安排即可";
    }

    /**
     * 家庭养护文案（I10）：天气不只影响订单建议，也改变「回家之后怎么养」的口径。
     * 与 {@link #advise} 的区别是这里给的是给已经收到花的人看的一句话提醒，不复述风险等级。
     */
    public static String careNote(Integer temperature, Integer humidity, String weather, String windPower) {
        if (temperature != null && temperature >= HOT_TEMP) {
            return "高温天请每天换水并剪根 2 厘米，花束远离阳台直射";
        }
        if (temperature != null && temperature <= COLD_TEMP) {
            return "低温天把花放在 8℃ 以上的室内，夜里别贴着窗缝";
        }
        if (humidity != null && humidity <= DRY_HUMIDITY) {
            return "空气干燥，除了换水还要给花瓣周围喷雾保湿";
        }
        if (isRainy(weather)) {
            return "雨天湿度高，水位比平时浅一点，避免茎秆泡烂";
        }
        if (windLevel(windPower) >= 5) {
            return "大风天请移入室内，远离空调与门口的对流风";
        }
        return "常规养护即可：隔天换水、斜剪根、远离水果";
    }

    /** 风力文本形如「≤3」「4-5」「6-7」，取区间上限；解析失败按 0 级 */
    public static int windLevel(String windPower) {
        if (windPower == null || windPower.isBlank()) {
            return 0;
        }
        String digits = windPower.replaceAll("[^0-9-]", "");
        if (digits.contains("-")) {
            String[] parts = digits.split("-");
            return parse(parts[parts.length - 1]);
        }
        return parse(digits);
    }

    private static int parse(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 花田成长的天气加成：晴天补光照、降水补水润，极端温度反而让花苗受压。
     * 这样「今天天气」会真实影响养成结果，而不只是一个展示条。
     */
    public record Growth(int sun, int water, int stress, String note) {
        public int bonus() {
            return sun + water - stress;
        }
    }

    public static Growth growth(String weather, Integer temperature) {
        boolean sunny = weather != null && (weather.contains("晴") || weather.contains("多云"));
        boolean rainy = isRainy(weather);
        int sun = sunny ? 2 : 0;
        int water = rainy ? 2 : 0;
        int stress = 0;
        String note = null;
        if (temperature != null && temperature >= HOT_TEMP) {
            stress = 2;
            note = "高温花苗受压";
        } else if (temperature != null && temperature <= COLD_TEMP) {
            stress = 2;
            note = "低温生长停滞";
        } else if (sunny) {
            note = "光照充足";
        } else if (rainy) {
            note = "雨水充沛";
        }
        return new Growth(sun, water, stress, note);
    }
}
