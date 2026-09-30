package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import org.liuym.flowerv1springboot.common.AmapClient;
import org.liuym.flowerv1springboot.common.CacheInvalidateRegistry;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Masking;
import org.liuym.flowerv1springboot.common.RichTextPolicy;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.common.ScheduledJobCollector;
import org.liuym.flowerv1springboot.common.ScheduledJobTracker;
import org.liuym.flowerv1springboot.common.SqlTrace;
import org.liuym.flowerv1springboot.common.TokenBucketRateLimiter;
import org.liuym.flowerv1springboot.config.RateLimitInterceptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 只读运行诊断（L04/L07/L08/L09/L10 的出口）。
 *
 * <p>定位是「运维与开发看数」而不是「监控体系」：不引 Prometheus、不做聚合存储，
 * 所有端点都只回答一个问题，答案来自内存里现成的计数器。
 *
 * <p>安全口径：全部挂在 /api/admin/** 下（鉴权拦截器已要求管理员角色），并额外调一次
 * {@link CurrentUser#requireAdmin}；返回值只出现计数、阈值、SQL 形状与状态字符串，
 * 不回业务数据、不回凭据、不回服务器路径；异常摘要统一过 {@link Masking} 脱敏。
 */
@RestController
@RequestMapping("/api/admin/diagnostics")
@Tag(name = "后台 · 运行诊断")
public class DiagnosticsController {

    private final ScheduledJobTracker jobTracker;
    private final ScheduledJobCollector jobCollector;
    private final CacheInvalidateRegistry cacheRegistry;
    private final RateLimitInterceptor rateLimitInterceptor;
    private final TokenBucketRateLimiter rateLimiter;
    private final AmapClient amapClient;

    public DiagnosticsController(ScheduledJobTracker jobTracker, ScheduledJobCollector jobCollector,
                                 CacheInvalidateRegistry cacheRegistry, RateLimitInterceptor rateLimitInterceptor,
                                 TokenBucketRateLimiter rateLimiter, AmapClient amapClient) {
        this.jobTracker = jobTracker;
        this.jobCollector = jobCollector;
        this.cacheRegistry = cacheRegistry;
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.rateLimiter = rateLimiter;
        this.amapClient = amapClient;
    }

    /** 一页看完各面板的关键数字，后台「系统运行台」首屏只要这一个请求 */
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview(HttpSession session) {
        CurrentUser.requireAdmin(session);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobs", jobTracker.summary());
        data.put("jobCollectorAttached", jobCollector.attached());
        data.put("sql", sqlThresholds());
        data.put("sqlSuspects", SqlTrace.recent(true).size());
        data.put("circuit", circuit());
        data.put("rateLimit", Map.of("enabled", rateLimitInterceptor.enabled(),
                "rules", rateLimitInterceptor.rules().size(), "trackedKeys", rateLimiter.trackedKeys()));
        data.put("cache", Map.of("caches", cacheRegistry.cacheNames(), "sizes", cacheRegistry.sizes()));
        data.put("richTextFields", RichTextPolicy.managedFields());
        return Result.ok(data);
    }

    /**
     * SQL / 慢查询面板（L08）：最近请求的条数与耗时，默认只回触发阈值的那些。
     *
     * <p>{@code all=true} 才回全量环形缓冲——默认收紧是因为这块缓冲里能看到接口路径与 SQL 形状，
     * 全量导出对后台页面没意义，对探测者有意义。
     */
    @GetMapping("/sql")
    public Result<Map<String, Object>> sql(HttpSession session,
                                           @RequestParam(defaultValue = "false") boolean all,
                                           @RequestParam(defaultValue = "10") int hot) {
        CurrentUser.requireAdmin(session);
        Map<String, Object> data = new LinkedHashMap<>(sqlThresholds());
        data.put("enabled", SqlTrace.isEnabled());
        data.put("recent", SqlTrace.recent(all ? false : true).stream().map(DiagnosticsController::rowOf).toList());
        data.put("hottest", SqlTrace.hottest(hot).stream().map(DiagnosticsController::rowOf).toList());
        return Result.ok(data);
    }

    /** 单条 SQL 形状的可读投影：只保留结构，字面量已在归一化阶段被替换成 ? */
    private static Map<String, Object> rowOf(SqlTrace.Snapshot snapshot) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("method", snapshot.method());
        row.put("path", snapshot.path());
        row.put("requestMs", snapshot.requestMs());
        row.put("sqlCount", snapshot.sqlCount());
        row.put("sqlMs", snapshot.sqlMs());
        row.put("slowestSqlMs", snapshot.slowestSqlMs());
        row.put("slowestSql", Masking.scrubText(snapshot.slowestSql()));
        row.put("topShape", Masking.scrubText(snapshot.topShape()));
        row.put("topRepeat", snapshot.topRepeat());
        row.put("nPlusOneSuspect", snapshot.nPlusOneSuspect());
        row.put("slowSqlSuspect", snapshot.slowSqlSuspect());
        return row;
    }

    private Map<String, Object> sqlThresholds() {
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("warnSqlCount", SqlTrace.thresholdSqlCount());
        thresholds.put("warnSqlMs", SqlTrace.thresholdSqlMillis());
        thresholds.put("warnRequestMs", SqlTrace.thresholdRequestMillis());
        thresholds.put("repeatThreshold", SqlTrace.thresholdRepeat());
        return thresholds;
    }

    /** 定时任务台账（L10） */
    @GetMapping("/jobs")
    public Result<List<ScheduledJobTracker.JobState>> jobs(HttpSession session) {
        CurrentUser.requireAdmin(session);
        return Result.ok(jobTracker.snapshot());
    }

    /** 单个任务最近若干次执行，后台页展开一行时用 */
    @GetMapping("/jobs/history")
    public Result<Map<String, Object>> jobHistory(HttpSession session, @RequestParam String job,
                                                  @RequestParam(defaultValue = "10") int limit) {
        CurrentUser.requireAdmin(session);
        if (!jobTracker.snapshot().stream().anyMatch(state -> state.job().equals(job))) {
            return Result.notFound("没有这个任务的记录");
        }
        return Result.ok(Map.of("job", job, "runs", jobTracker.recentOf(job, limit)));
    }

    /** 外部依赖面板（L07）：熔断状态 + 被挡下的调用数（=省下的配额） + 最近一次失败原因 */
    @GetMapping("/deps")
    public Result<Map<String, Object>> deps(HttpSession session) {
        CurrentUser.requireAdmin(session);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("amapConfigured", amapClient.available());
        data.put("circuit", circuit());
        data.put("lookups", amapClient.stats().lookups());
        data.put("hitRate", amapClient.stats().hitRate());
        amapClient.lastIssue().ifPresent(issue -> data.put("lastIssue", amapClient.scrub(issue)));
        return Result.ok(data);
    }

    private Map<String, Object> circuit() {
        var snapshot = amapClient.circuit();
        Map<String, Object> circuit = new LinkedHashMap<>();
        circuit.put("state", snapshot.state().name());
        circuit.put("degraded", snapshot.degraded());
        circuit.put("consecutiveFailures", snapshot.consecutiveFailures());
        circuit.put("totalFailures", snapshot.totalFailures());
        circuit.put("blockedCalls", snapshot.totalBlocked());
        circuit.put("trips", snapshot.trips());
        circuit.put("openRemainingMs", snapshot.openRemainingMs());
        circuit.put("failureThreshold", amapClient.circuitFailureThreshold());
        circuit.put("openSeconds", amapClient.circuitOpenSeconds());
        return circuit;
    }

    /** 缓存失效清单（L09）：哪类写操作清哪几张，以及现在各装了多少条 */
    @GetMapping("/cache")
    public Result<Map<String, Object>> cache(HttpSession session) {
        CurrentUser.requireAdmin(session);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("plan", cacheRegistry.plan());
        data.put("owners", cacheRegistry.owners());
        data.put("sizes", cacheRegistry.sizes());
        return Result.ok(data);
    }

    /**
     * 手动全量刷新缓存（L09）：这是本控制器唯一的写操作，且只清本地缓存、不碰数据。
     * 用 POST 而不是 GET：语义上是变更，也要进后台审计。
     */
    @PostMapping("/cache/refresh")
    public Result<Map<String, Object>> refreshCache(HttpSession session) {
        CurrentUser.requireAdmin(session);
        return Result.ok("缓存已刷新", Map.of("evicted", cacheRegistry.invalidateAll()));
    }

    /** 限流面板（L04）：当前生效的规则与阈值，回答「这里为什么会 429」 */
    @GetMapping("/rate-limit")
    public Result<Map<String, Object>> rateLimit(HttpSession session) {
        CurrentUser.requireAdmin(session);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", rateLimitInterceptor.enabled());
        data.put("trackedKeys", rateLimiter.trackedKeys());
        data.put("rules", rateLimitInterceptor.rules().stream().map(rule -> {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("name", rule.name());
            row.put("pattern", rule.pattern());
            row.put("scope", rule.scope().name());
            row.put("capacity", rule.capacity());
            row.put("refillPerSecond", rule.refill());
            row.put("message", rule.message());
            return row;
        }).toList());
        return Result.ok(data);
    }

    /** 富文本白名单清单（L06） */
    @GetMapping("/rich-text")
    public Result<Map<String, Object>> richText(HttpSession session) {
        CurrentUser.requireAdmin(session);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fields", RichTextPolicy.catalog());
        data.put("counts", RichTextPolicy.bucketCounts());
        return Result.ok(data);
    }
}
