package org.liuym.flowerv1springboot.common;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 请求级 SQL 计数与耗时（L08）：回答「这个接口到底打了多少条 SQL、哪一条在重复」。
 *
 * <p>实现约束：不引新依赖，只用 Hibernate 的 {@code StatementInspector} 作为计数点。
 * 单条 SQL 的耗时是「本次 inspect 到下次 inspect 的间隔」——它包含了执行 + 取结果 + 映射，
 * 所以叫「区间耗时」而不是「数据库执行时间」，用来找慢点足够，用来说服 DBA 不够。
 * 最后一条 SQL 没有下一个边界，不计耗时。
 *
 * <p>N+1 判定：同一条「SQL 形状」在一个请求里出现 ≥ 阈值次即可疑。形状比较前先把数字与引号字面量归一、
 * 把 IN 列表折叠成一组，否则 {@code in (1,2,3)} 与 {@code in (4,5)} 会被当成两条不同的 SQL。
 *
 * <p>开销：一次请求多两次 ThreadLocal 读写与若干字符串处理，相对建连与渲染可以忽略；
 * 全局开关 {@code app.sql-trace.enabled} 关掉后 inspect 直接返回。
 */
public final class SqlTrace {

    private static final Pattern NUMBER = Pattern.compile("\\b\\d+\\b");
    private static final Pattern QUOTED = Pattern.compile("'[^']*'");
    private static final Pattern IN_LIST = Pattern.compile("\\bin\\s*\\([^)]*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WS = Pattern.compile("\\s+");

    /** 活跃跟踪，ThreadLocal 保证同请求同线程归零；异步分支不在本批覆盖范围 */
    private static final ThreadLocal<Active> CURRENT = new ThreadLocal<>();

    /** 最近请求的环形缓冲：只留最慢/最多 SQL 的那些，供后台诊断页直读 */
    private static final Deque<Snapshot> RECENT = new ArrayDeque<>();
    private static final int RECENT_KEEP = 60;

    private static volatile boolean enabled = true;
    private static volatile int repeatThreshold = 5;
    private static volatile int warnSqlCount = 15;
    private static volatile long warnSqlMillis = 500;
    private static volatile long warnRequestMillis = 1500;

    private SqlTrace() {
    }

    /** 一次请求的结论。 */
    public record Snapshot(String method, String path, long requestMs, int sqlCount, long sqlMs, long slowestSqlMs,
                           String slowestSql, String topShape, int topRepeat, boolean nPlusOneSuspect,
                           boolean slowSqlSuspect) {

        /** 给日志用的一行摘要（不含任何参数值，只有形状） */
        public String summary() {
            return method + " " + path + " 用时" + requestMs + "ms SQL" + sqlCount + "条/" + sqlMs
                    + "ms 最慢" + slowestSqlMs + "ms";
        }
    }

    private static final class Active {
        private final String method;
        private final String path;
        private final long startedAt;
        private long lastBoundary;
        private int count;
        private long totalNanos;
        private long slowestNanos;
        private String slowestSql = "";
        private final Map<String, Integer> shapes = new LinkedHashMap<>();

        private Active(String method, String path) {
            this.method = method;
            this.path = path;
            this.startedAt = System.nanoTime();
            this.lastBoundary = this.startedAt;
        }
    }

    public static void configure(boolean on, int repeats, int sqlCountWarn, long sqlMillisWarn, long requestMillisWarn) {
        enabled = on;
        repeatThreshold = Math.max(2, repeats);
        warnSqlCount = Math.max(1, sqlCountWarn);
        warnSqlMillis = Math.max(1, sqlMillisWarn);
        warnRequestMillis = Math.max(1, requestMillisWarn);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void begin(String method, String path) {
        if (!enabled) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(new Active(method, path));
    }

    /** 由 StatementInspector 调用：无跟踪上下文（定时任务、启动期）时什么也不做 */
    public static void recordSql(String sql) {
        Active active = CURRENT.get();
        if (active == null || sql == null || sql.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        long span = now - active.lastBoundary;
        active.lastBoundary = now;
        active.count++;
        // 第一条之前夹的是业务代码 + 连接获取，量级正常；超过 1 秒才当成慢 SQL 记录，免得把首次建连算成慢查询
        if (active.count > 1) {
            active.totalNanos += span;
            if (span > active.slowestNanos) {
                active.slowestNanos = span;
                active.slowestSql = abbreviate(sql);
            }
        }
        String shape = shape(sql);
        active.shapes.merge(shape, 1, Integer::sum);
    }

    /** 收尾并返回结论，同时把值得看的记进环形缓冲 */
    public static Snapshot finish() {
        Active active = CURRENT.get();
        CURRENT.remove();
        if (active == null) {
            return null;
        }
        long requestMs = (System.nanoTime() - active.startedAt) / 1_000_000L;
        String topShape = null;
        int topRepeat = 0;
        for (Map.Entry<String, Integer> entry : active.shapes.entrySet()) {
            if (entry.getValue() > topRepeat) {
                topRepeat = entry.getValue();
                topShape = entry.getKey();
            }
        }
        Snapshot snapshot = new Snapshot(active.method, active.path, requestMs, active.count,
                active.totalNanos / 1_000_000L, active.slowestNanos / 1_000_000L, active.slowestSql,
                topShape, topRepeat, topRepeat >= repeatThreshold,
                active.slowestNanos / 1_000_000L >= warnSqlMillis);
        if (active.count >= warnSqlCount || snapshot.slowestSqlMs() >= warnSqlMillis || requestMs >= warnRequestMillis) {
            // 超阈值才 WARN：正常页面的 INFO 没人看，看的人只想知道异常的那几条
            org.slf4j.LoggerFactory.getLogger(SqlTrace.class)
                    .warn("慢请求/多 SQL 探测 {}", snapshot.summary());
        }
        if (snapshot.nPlusOneSuspect()) {
            org.slf4j.LoggerFactory.getLogger(SqlTrace.class)
                    .warn("疑似 N+1：{} 里同一条 SQL 形状重复 {} 次 -> {}", snapshot.path(), topRepeat, topShape);
        }
        remember(snapshot);
        return snapshot;
    }

    /** 当前线程正在跟踪的第几条 SQL：给「顺手取一下上下文」的调用方用 */
    public static int currentCount() {
        Active active = CURRENT.get();
        return active == null ? 0 : active.count;
    }

    /** 丢弃当前上下文（异步派发、错误页二次进入时避免重复计数） */
    public static void abandon() {
        CURRENT.remove();
    }

    private static void remember(Snapshot snapshot) {
        synchronized (RECENT) {
            RECENT.addFirst(snapshot);
            while (RECENT.size() > RECENT_KEEP) {
                RECENT.removeLast();
            }
        }
    }

    /**
     * 最近请求摘要（新→旧）。
     *
     * @param onlySuspects true 时只回触发阈值的那些，后台页默认这么看
     */
    public static List<Snapshot> recent(boolean onlySuspects) {
        List<Snapshot> copy;
        synchronized (RECENT) {
            copy = new ArrayList<>(RECENT);
        }
        if (!onlySuspects) {
            return copy;
        }
        copy.removeIf(item -> !(item.nPlusOneSuspect() || item.slowSqlSuspect()
                || item.sqlCount() >= warnSqlCount || item.requestMs() >= warnRequestMillis));
        return copy;
    }

    /** 只看 SQL 条数最多的那些，N+1 排查从这一列开始最有效 */
    public static List<Snapshot> hottest(int limit) {
        synchronized (RECENT) {
            return RECENT.stream()
                    .sorted((a, b) -> Integer.compare(b.sqlCount(), a.sqlCount()))
                    .limit(Math.max(1, limit))
                    .toList();
        }
    }

    public static void clearRecent() {
        synchronized (RECENT) {
            RECENT.clear();
        }
    }

    public static int thresholdSqlCount() {
        return warnSqlCount;
    }

    public static long thresholdSqlMillis() {
        return warnSqlMillis;
    }

    public static long thresholdRequestMillis() {
        return warnRequestMillis;
    }

    public static int thresholdRepeat() {
        return repeatThreshold;
    }

    /**
     * SQL 归一化成「形状」：字面量换成 ?、IN 列表折叠、空白压平。
     * 折叠 IN 是这里的关键一步——列表长度不同但结构相同的语句必须算同一条，否则 N+1 永远测不出来。
     */
    static String shape(String sql) {
        String one = WS.matcher(sql.trim()).replaceAll(" ");
        one = IN_LIST.matcher(one).replaceAll("in (?)");
        one = QUOTED.matcher(one).replaceAll("?");
        one = NUMBER.matcher(one).replaceAll("?");
        return abbreviate(one);
    }

    private static String abbreviate(String value) {
        return value.length() <= 220 ? value : value.substring(0, 219) + "…";
    }
}
