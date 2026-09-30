package org.liuym.flowerv1springboot.common;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * 我的花田数值规则：浇水次数、连续培育、天气加成、疏于照料的回落，全部集中在这里。
 *
 * <p>刻意做成纯函数：成长值是「存量 + 距上次浇水的天数」算出来的派生量，
 * 只有浇水/兑换那一刻才落库，规则散到页面或服务里就会出现「页面显示 82、兑换时按 78 判未成熟」这类对不上的账。
 */
public final class GardenPolicy {

    private GardenPolicy() {
    }

    /** 每日免费浇水次数上限 */
    public static final int WATER_PER_DAY = 3;
    /** 单次浇水的基础成长值 */
    public static final int GROWTH_PER_WATER = 6;
    /** 连续培育每天额外加值，最多累计到 CAP */
    public static final int STREAK_STEP = 2;
    public static final int STREAK_CAP = 6;
    /** 超过这么多天没管才开始回落，给用户留出周末 */
    public static final int IDLE_GRACE_DAYS = 2;
    /** 回落速率：每多闲置一天扣掉的成长值 */
    public static final int DECAY_PER_DAY = 4;
    /** 成熟兑换后的券有效期由 coupon 模板决定，这里只负责判定是否成熟 */

    /** 地块数量（I11）：普通用户一块，会员解锁第二块，两块地各自独立计次与连续培育 */
    public static final int SLOTS_FREE = 1;
    public static final int SLOTS_VIP = 2;

    /**
     * 花田要的预报条数（I08）：4 条 = 今天 + 未来 3 日。
     * 高德预报的第一行通常是「今天」，但在深夜时段会直接把明天排到第一位，
     * 所以这里多取一条、由日期比对决定哪一行标成「今天」，而不是假定下标 0 就是今天。
     */
    public static final int FORECAST_ROWS = 4;

    private static final String[] WEEKDAY = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    public static final String GIFT_PENDING = "pending";
    public static final String GIFT_ACCEPTED = "accepted";
    public static final String GIFT_DECLINED = "declined";
    public static final String GIFT_CANCELED = "canceled";

    public static int slotsFor(boolean vip) {
        return vip ? SLOTS_VIP : SLOTS_FREE;
    }

    /** 地块是否可开：编号越界或非会员开第二块都不行 */
    public static boolean canOpenSlot(int slot, boolean vip) {
        return slot >= 1 && slot <= slotsFor(vip);
    }

    public static String slotName(int slot) {
        return slot <= 1 ? "1 号地" : slot + " 号地";
    }

    /** 锁定地块的提示文案：把「为什么点不动」说清楚，而不是留一个灰按钮 */
    public static String lockedNote(int slot) {
        return "第 " + slot + " 块地需升级为会员后解锁（会员可同时培育 " + SLOTS_VIP + " 株）";
    }

    /**
     * 预报某一日的相对叫法（I08）：今天 / 明天 / 后天 / 周几，跨年才退回日期原文。
     *
     * <p>按日期差而不是数组下标判定，是因为高德的预报首行在深夜时段会变成「明天」，
     * 写死下标会让页面把明天当今天推荐配送时段。
     */
    public static String forecastDayLabel(String rawDate, LocalDate today) {
        LocalDate day = parseDate(rawDate);
        if (day == null) {
            return rawDate == null ? "" : rawDate;
        }
        if (today == null) {
            return day.getMonthValue() + " 月 " + day.getDayOfMonth() + " 日";
        }
        long diff = ChronoUnit.DAYS.between(today, day);
        if (diff == 0) {
            return "今天";
        }
        if (diff == 1) {
            return "明天";
        }
        if (diff == 2) {
            return "后天";
        }
        if (diff == -1) {
            return "昨天";
        }
        return day.getYear() == today.getYear()
                ? WEEKDAY[day.getDayOfWeek().getValue() - 1]
                : day.getMonthValue() + " 月 " + day.getDayOfMonth() + " 日";
    }

    /** 预报行是否就是今天（I08）：页面据此把「今日实况」和「未来三日」拼成一条连续的时间轴 */
    public static boolean isSameDay(String rawDate, LocalDate today) {
        LocalDate day = parseDate(rawDate);
        return day != null && day.equals(today);
    }

    /** 高德给的 date 形如 2026-09-30；异常形状一律当「不知道」而不是抛错，天气不该打断页面 */
    public static LocalDate parseDate(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(rawDate.trim());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** 转赠确认态文案（I13） */
    public static String giftStateLabel(String state) {
        if (state == null) {
            return "";
        }
        return switch (state) {
            case GIFT_PENDING -> "待对方确认";
            case GIFT_ACCEPTED -> "对方已收下";
            case GIFT_DECLINED -> "对方已婉拒，花已退回你的花田";
            case GIFT_CANCELED -> "你已撤回，花回到可兑换状态";
            default -> "";
        };
    }

    /**
     * 当日花田操作建议（I10）：天气影响成长值这件事，必须同时反映到人能看懂的一句话上，
     * 否则用户只看到「+14」却不知道多出来的 4 点从哪来。文案按花苗习性分化，避免四种植株共用一句模板。
     */
    public static String careNote(Seed seed, FlowerCarePolicy.Growth growth) {
        String name = seed == null ? "花苗" : seed.name();
        if (growth == null) {
            return "暂无今日天气数据，按每天 " + WATER_PER_DAY + " 次的节奏浇水即可";
        }
        if (growth.stress() > 0) {
            return "今日" + (growth.note() == null ? "极端天气" : growth.note())
                    + "，花苗受压，建议改在早晚较凉时段浇水";
        }
        if (seed != null && growth.water() > 0 && "hydra".equalsIgnoreCase(seed.code())) {
            return "绣球喜水，今天的降水正好补水润，照常浇水就能拿到最高加成";
        }
        if (seed != null && growth.sun() > 0 && "rose".equalsIgnoreCase(seed.code())) {
            return "日照充足，玫瑰上色快，记得隔天转一下朝向让受光均匀";
        }
        if (growth.sun() > 0 && growth.water() > 0) {
            return "光照与水润同时在线，今天浇水最划算";
        }
        if (growth.sun() > 0) {
            return "今日光照充足，" + name + "长得稳，按时浇水即可";
        }
        if (growth.water() > 0) {
            return "今日降水补水润，" + name + "不必额外多浇";
        }
        return "今日天气平和，按每天 " + WATER_PER_DAY + " 次的节奏浇水即可";
    }

    public static final String STAGE_SEED = "seed";
    public static final String STAGE_SPROUT = "sprout";
    public static final String STAGE_BUD = "bud";
    public static final String STAGE_BLOOM = "bloom";

    /** 花苗字典：code 同时是 flower_plot.seed_code，奖励按 couponCode 指向的券模板发放 */
    public record Seed(String code, String name, String icon, String originName, int needGrowth,
                       String couponCode, String rewardText, String note) {
    }

    public static final List<Seed> SEEDS = List.of(
            new Seed("rose", "高原玫瑰", "fa-heart", "昆明斗南花卉基地", 100, "GARDEN-ROSE", "¥20 玫瑰专享券",
                    "日照 2200 小时的花青素，成熟约需 6 天"),
            new Seed("tulip", "冷凉郁金香", "fa-adjust", "新疆伊犁郁金香基地", 120, "GARDEN-TULIP", "¥25 郁金香专享券",
                    "需要更长春化周期，成熟约需 7 天"),
            new Seed("hydra", "三圣乡绣球", "fa-tint", "成都三圣乡花卉基地", 140, "GARDEN-HYDRA", "¥30 绣球专享券",
                    "喜水，雨天加成最明显，成熟约需 8 天"),
            new Seed("sun", "向阳向日葵", "fa-sun-o", "昆明斗南花卉基地", 80, "GARDEN-SUN", "¥15 向日葵专享券",
                    "长得最快，成熟约需 5 天"));

    public static Optional<Seed> seed(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return SEEDS.stream().filter(s -> s.code().equalsIgnoreCase(code.trim())).findFirst();
    }

    /** 剩余可浇次数：跨到第二天自然重置 */
    public static int remainingWaterToday(Integer waterTimesToday, LocalDate wateredOn, LocalDate today) {
        int used = wateredOn != null && wateredOn.equals(today) ? (waterTimesToday == null ? 0 : waterTimesToday) : 0;
        return Math.max(0, WATER_PER_DAY - used);
    }

    /** 当天是连续培育的第几天：昨天浇过就 +1，断了就从 1 重新数 */
    public static int nextStreak(Integer currentStreak, LocalDate wateredOn, LocalDate today) {
        int current = currentStreak == null ? 0 : currentStreak;
        if (wateredOn == null) {
            return 1;
        }
        if (wateredOn.equals(today)) {
            return Math.max(1, current);
        }
        return ChronoUnit.DAYS.between(wateredOn, today) == 1 ? current + 1 : 1;
    }

    public static int streakBonus(int streakDays) {
        return Math.min(STREAK_CAP, Math.max(0, streakDays - 1) * STREAK_STEP);
    }

    /**
     * 一次浇水能涨多少：基础值 + 连续培育加成 + 当日天气加成，最低给 1，不让好意头变成零收益。
     *
     * @param weatherBonus 由 {@link FlowerCarePolicy#growth} 得出，晴天多光照、雨天多水润、极端天气为负
     */
    public static int waterGain(int streakDays, int weatherBonus) {
        return Math.max(1, GROWTH_PER_WATER + streakBonus(streakDays) + weatherBonus);
    }

    /** 闲置回落后的实际成长值：没到宽限期原样返回，超了就按天扣，最低到 0 */
    public static int effectiveGrowth(int growth, LocalDate wateredOn, LocalDate sowedOn, LocalDate today) {
        LocalDate anchor = wateredOn != null ? wateredOn : sowedOn;
        if (anchor == null || today == null) {
            return growth;
        }
        long idle = ChronoUnit.DAYS.between(anchor, today);
        if (idle <= IDLE_GRACE_DAYS) {
            return Math.max(0, growth);
        }
        return Math.max(0, growth - (int) ((idle - IDLE_GRACE_DAYS) * DECAY_PER_DAY));
    }

    /** 已闲置天数，页面用它解释「为什么比昨天矮了一截」 */
    public static long idleDays(LocalDate wateredOn, LocalDate sowedOn, LocalDate today) {
        LocalDate anchor = wateredOn != null ? wateredOn : sowedOn;
        if (anchor == null || today == null) {
            return 0;
        }
        return Math.max(0, ChronoUnit.DAYS.between(anchor, today));
    }

    /** 生长阶段按占成熟阈值的比例给，四段对应前端四套 SVG */
    public static String stageFor(int growth, Seed seed) {
        int need = seed == null ? 100 : seed.needGrowth();
        double ratio = need <= 0 ? 1 : (double) growth / need;
        if (ratio >= 1) {
            return STAGE_BLOOM;
        }
        if (ratio >= 0.6) {
            return STAGE_BUD;
        }
        if (ratio >= 0.25) {
            return STAGE_SPROUT;
        }
        return STAGE_SEED;
    }

    public static boolean mature(int growth, Seed seed) {
        return seed != null && growth >= seed.needGrowth();
    }

    /** 还差多少成长值 / 大约还需几天，用来写「今日浇水后即可成熟」这类有依据的提示 */
    public static String estimate(int growth, Seed seed) {
        if (seed == null) {
            return "";
        }
        int lack = seed.needGrowth() - growth;
        if (lack <= 0) {
            return "已经成熟，可以兑换或送人";
        }
        int perDay = WATER_PER_DAY * GROWTH_PER_WATER;
        int days = Math.max(1, (int) Math.ceil(lack / (double) perDay));
        return "还差 " + lack + " 点成长值，按时浇水约需 " + days + " 天";
    }
}
