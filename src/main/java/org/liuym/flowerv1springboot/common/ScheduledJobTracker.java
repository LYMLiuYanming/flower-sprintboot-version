package org.liuym.flowerv1springboot.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定时任务运行台账（L10）：每个 @Scheduled 方法的「最近执行时间 / 耗时 / 结果 / 处理条数 / 异常摘要」。
 *
 * <p>采集不侵入业务代码：给调度线程池装一个 TaskDecorator（{@link ScheduledJobCollector}），
 * 因此不需要任何任务类配合，新增任务自动进台账。
 *
 * <p>「处理条数」有两路来源：
 * <ol>
 *   <li>{@link SqlTrace} 的同线程 SQL 计数——装饰器能独立拿到，任何任务都有值；</li>
 *   <li>任务体自己调 {@link #report(long)} 上报的业务条数——更准，但需要接线（订单关单、自动确认收货、
 *       注销冷静期三处的接线点写在施工报告里，本批不抢他人文件）。</li>
 * </ol>
 *
 * <p>每个任务只留最近 N 次执行，进程重启清零：台账回答的是「现在有没有在跑、跑得怎么样」，
 * 不承担历史审计（那是 admin_audit_log 的职责）。
 */
@Component
public class ScheduledJobTracker {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobTracker.class);

    /**
     * 一次执行记录。
     *
     * @param finishedAt 结束时刻
     * @param durationMs 耗时
     * @param outcome    ok / error
     * @param error      异常摘要（类名 + 一句话，已脱敏，不带堆栈与参数值）
     * @param sqlCount   本次执行触发的 SQL 条数
     * @param reported   任务上报的业务处理条数，未上报为 null
     */
    public record Run(LocalDateTime finishedAt, long durationMs, String outcome, String error,
                      int sqlCount, Long reported) {
    }

    /** 单个任务的汇总视图，只读接口与后台页共用同一份 */
    public record JobState(String job, long runs, long failures, long slowRuns,
                           LocalDateTime lastRunAt, Long lastDurationMs, String lastOutcome, String lastError,
                           Integer lastSqlCount, Long lastReported, LocalDateTime lastSuccessAt,
                           long avgDurationMs, long maxDurationMs, boolean healthy, String healthNote) {

        /** 距上次成功过了多久（分钟）；从未成功返回 -1，页面显示「未见成功执行」 */
        public long minutesSinceSuccess() {
            return lastSuccessAt == null ? -1 : Duration.between(lastSuccessAt, LocalDateTime.now()).toMinutes();
        }
    }

    /** 一次执行的中间态 */
    public static final class Context {
        private final String job;
        private final long startedAt;

        private Context(String job, long startedAt) {
            this.job = job;
            this.startedAt = startedAt;
        }

        public String job() {
            return job;
        }
    }

    private static final class History {
        private final Deque<Run> runs = new ArrayDeque<>();
        private long total;
        private long failures;
        private long slow;
        private long sumDuration;
        private long maxDuration;
        private LocalDateTime lastSuccessAt;
    }

    private final Map<String, History> histories = new ConcurrentHashMap<>();
    /** 待认领的上报值：任务体里调 report()，装饰器在结束时取走，靠 ThreadLocal 配对 */
    private final ThreadLocal<Long> pendingReport = new ThreadLocal<>();

    private final LocalDateTime startedAt = LocalDateTime.now();
    private final int keep;
    private final long slowMs;

    public ScheduledJobTracker(@Value("${app.job-tracker.history:20}") int keep,
                              @Value("${app.job-tracker.slow-ms:30000}") long slowMs) {
        this.keep = Math.max(1, keep);
        this.slowMs = Math.max(100, slowMs);
    }

    /**
     * 任务开始：顺手起一个 SQL 跟踪上下文。
     *
     * <p>调度线程不经过 DispatcherServlet，本来没有 SqlTrace 上下文，
     * 不 begin 就永远统计不到任务内部的 SQL 条数——而这正是判断「关单扫描有没有退化成全表逐行」的关键指标。
     */
    public Context start(String job) {
        SqlTrace.begin("JOB", job);
        pendingReport.remove();
        return new Context(job, System.nanoTime());
    }

    /** 任务体上报本次处理的业务条数（订单数、券数…）；未接线时这一列留空 */
    public void report(long processed) {
        pendingReport.set(processed);
    }

    /** 收尾：落一条执行记录。必须在 finally 里调用，否则线程复用时上下文会串到下一个任务 */
    public void finish(Context context, Throwable error) {
        long durationMs = (System.nanoTime() - context.startedAt) / 1_000_000L;
        int sqlCount = SqlTrace.currentCount();
        SqlTrace.abandon();
        Long reported = pendingReport.get();
        pendingReport.remove();
        String summary = error == null ? "" : Masking.scrubText(Masking.brief(error));
        History history = histories.computeIfAbsent(context.job, key -> new History());
        synchronized (history) {
            history.total++;
            history.sumDuration += durationMs;
            history.maxDuration = Math.max(history.maxDuration, durationMs);
            if (durationMs >= slowMs) {
                history.slow++;
            }
            if (error != null) {
                history.failures++;
            } else {
                history.lastSuccessAt = LocalDateTime.now();
            }
            history.runs.addFirst(new Run(LocalDateTime.now(), durationMs, error == null ? "ok" : "error",
                    summary, sqlCount, reported));
            while (history.runs.size() > keep) {
                history.runs.removeLast();
            }
        }
        if (error != null) {
            log.warn("定时任务 {} 执行失败，耗时 {}ms：{}", context.job, durationMs, summary);
        } else if (durationMs >= slowMs) {
            log.warn("定时任务 {} 执行偏慢：{}ms（阈值 {}ms）", context.job, durationMs, slowMs);
        }
    }

    /** 台账快照（任务名字典序），只读接口与后台页共用 */
    public List<JobState> snapshot() {
        List<JobState> list = new ArrayList<>(histories.size());
        for (Map.Entry<String, History> entry : histories.entrySet()) {
            History history = entry.getValue();
            Run last;
            long runs;
            long failures;
            long slow;
            long avg;
            long max;
            LocalDateTime lastSuccess;
            synchronized (history) {
                last = history.runs.peekFirst();
                runs = history.total;
                failures = history.failures;
                slow = history.slow;
                max = history.maxDuration;
                lastSuccess = history.lastSuccessAt;
                avg = history.total == 0 ? 0 : history.sumDuration / history.total;
            }
            boolean healthy = lastSuccess != null
                    && minutesSince(lastSuccess) < Math.max(120, expectedIntervalMinutes(entry.getKey()) * 3L);
            list.add(new JobState(entry.getKey(), runs, failures, slow,
                    last == null ? null : last.finishedAt(), last == null ? null : last.durationMs(),
                    last == null ? "-" : last.outcome(), last == null ? "" : last.error(),
                    last == null ? null : last.sqlCount(), last == null ? null : last.reported(),
                    lastSuccess, avg, max, healthy, healthNote(healthy, failures, last)));
        }
        list.sort((a, b) -> a.job().compareTo(b.job()));
        return list;
    }

    private static String healthNote(boolean healthy, long failures, Run last) {
        if (!healthy) {
            return failures == 0 ? "尚无成功记录" : "距上次成功已超过 3 倍预期周期";
        }
        if (last != null && "error".equals(last.outcome())) {
            return "最近一次失败，但整体仍在跑";
        }
        return "正常";
    }

    /**
     * 任务的名义周期（分钟），只用于「多久没跑算异常」的宽松判定。
     * 兜底给 24 小时：判错方向的代价应该是漏报而不是半夜假告警。
     */
    private long expectedIntervalMinutes(String job) {
        String key = job.toLowerCase(java.util.Locale.ROOT);
        if (key.contains("cancel") || key.contains("timeout")) {
            return 1;
        }
        if (key.contains("receive") || key.contains("confirm")) {
            return 30;
        }
        return 60 * 24;
    }

    private static long minutesSince(LocalDateTime time) {
        return Duration.between(time, LocalDateTime.now()).toMinutes();
    }

    /** 单任务最近若干次执行，后台页展开一行时用 */
    public List<Run> recentOf(String job, int limit) {
        History history = histories.get(job);
        if (history == null) {
            return List.of();
        }
        synchronized (history) {
            int size = Math.min(history.runs.size(), Math.max(1, limit));
            return new ArrayList<>(history.runs).subList(0, size);
        }
    }

    public Map<String, Object> summary() {
        List<JobState> states = snapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobs", states.size());
        out.put("unhealthy", states.stream().filter(state -> !state.healthy()).count());
        out.put("totalRuns", states.stream().mapToLong(JobState::runs).sum());
        out.put("totalFailures", states.stream().mapToLong(JobState::failures).sum());
        out.put("slowThresholdMs", slowMs);
        out.put("keepPerJob", keep);
        out.put("trackerStartedAt", startedAt);
        return out;
    }

    /** 台账整体清空（演练或长时间挂机后重开观察窗口） */
    public void clear() {
        histories.clear();
    }

    /** 装饰器在任务进入前调用，用于把上报值挂到正确的线程上 */
    public void bindThread() {
        pendingReport.remove();
    }
}
