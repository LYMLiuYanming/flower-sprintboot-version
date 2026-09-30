package org.liuym.flowerv1springboot.common;

import org.liuym.flowerv1springboot.config.CacheConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动自检（L15）：探一遍「缺了就跑不动」的东西，把结论存成一份可读快照。
 *
 * <p>三条设计约束：
 * <ol>
 *   <li><b>只报状态不报值</b>：密钥类检查的输出只有「已配置 / 未配置」，绝不回显长度、前后缀或哈希——
 *       这类接口通常就挂在后台页面上，任何一点侧信道都算泄露；</li>
 *   <li><b>异常不崩启动</b>：探测失败汇总成 WARN，进程照常起来。启动期 DB 抖一下就把应用打死，
 *       运维只能靠重启碰运气，比带病起来更糟；</li>
 *   <li><b>可重复调用</b>：后台「立即复检」按钮和启动监听器共用同一个入口，逻辑只有一份。</li>
 * </ol>
 */
@Component
public class SystemHealthProbe {

    private static final Logger log = LoggerFactory.getLogger(SystemHealthProbe.class);

    /** 单项检查结论。@param detail 已脱敏，永不含凭据值 */
    public record Check(String name, String label, String status, String detail) {

        public static Check ok(String label, String detail) {
            return new Check(label, label, "ok", detail);
        }

        public static Check warn(String name, String label, String detail) {
            return new Check(name, label, "warn", detail);
        }

        public static Check fail(String name, String label, String detail) {
            return new Check(name, label, "fail", detail);
        }

        public boolean healthy() {
            return "ok".equals(status);
        }
    }

    /** 一次自检的整体结论 */
    public record Report(LocalDateTime at, List<Check> checks) {

        public long failed() {
            return checks.stream().filter(check -> "fail".equals(check.status())).count();
        }

        public long warned() {
            return checks.stream().filter(check -> "warn".equals(check.status())).count();
        }

        public boolean healthy() {
            return failed() == 0;
        }
    }

    private final JdbcTemplate jdbcTemplate;
    private final AmapClient amapClient;
    private final String reviewUploadDir;
    private final String rememberSecretConfigured;

    private volatile Report last = new Report(null, List.of());

    public SystemHealthProbe(JdbcTemplate jdbcTemplate, AmapClient amapClient,
                             @Value("${app.review-upload.dir:upload/review}") String reviewUploadDir,
                             @Value("${app.remember-me.secret:}") String rememberSecret) {
        this.jdbcTemplate = jdbcTemplate;
        this.amapClient = amapClient;
        this.reviewUploadDir = reviewUploadDir;
        // 只保留「有没有配」这一个布尔信息，值本身不留在本对象里，也就没有被顺手打印出去的可能
        this.rememberSecretConfigured = rememberSecret == null || rememberSecret.isBlank() ? "" : "configured";
    }

    /** 最近一次自检结果（不重新探测），后台页首屏用它避免重复打库 */
    public Report last() {
        return last;
    }

    /** 完整探测并缓存结论 */
    public Report check() {
        List<Check> checks = new ArrayList<>();
        checks.add(database());
        checks.add(amapKey());
        checks.add(uploadDir());
        checks.add(cacheLayer());
        checks.add(rememberMeSecret());
        Report report = new Report(LocalDateTime.now(), List.copyOf(checks));
        this.last = report;
        return report;
    }

    private Check database() {
        try {
            // select 1 足够回答「连接与会话是否可用」；版本串留给 /actuator/info，自检不掺进去
            jdbcTemplate.queryForObject("select 1", Integer.class);
            return Check.ok("数据库", "连接正常");
        } catch (RuntimeException e) {
            return Check.fail("database", "数据库", "连不上：" + Masking.brief(e));
        }
    }

    /** 高德服务端密钥：只回答配了没配，值与长度都不出网 */
    private Check amapKey() {
        return amapClient.available()
                ? Check.ok("高德密钥", "已配置，地理编码与天气可用")
                : Check.warn("amap-key", "高德密钥", "未配置，地图/天气按文字降级（不影响下单主链路）");
    }

    private Check uploadDir() {
        try {
            Path path = Paths.get(reviewUploadDir);
            Path dir = path.isAbsolute() ? path : Paths.get(System.getProperty("user.dir"), reviewUploadDir);
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir);
            }
            boolean writable = Files.isWritable(dir);
            return writable ? Check.ok("上传目录", "可写")
                    : Check.fail("upload-dir", "上传目录", "已存在但不可写，上传会失败");
        } catch (Exception e) {
            return Check.fail("upload-dir", "上传目录", "不可用：" + Masking.brief(e));
        }
    }

    private Check cacheLayer() {
        List<String> names = List.of(CacheConfig.CATEGORIES, CacheConfig.BANNERS, CacheConfig.NOTICES,
                CacheConfig.STATS);
        return Check.ok("本地缓存", "四张缓存已就位：" + String.join(" / ", names));
    }

    /** 记住我签名密钥：留空意味着每次启动随机，重启即全站失效，生产必须固定 */
    private Check rememberMeSecret() {
        return rememberSecretConfigured.isEmpty()
                ? Check.warn("remember-secret", "记住我密钥", "未固定，重启后所有「记住我」设备需重新登录")
                : Check.ok("记住我密钥", "已配置");
    }

    /** 启动汇总：一行日志说清有没有需要人看的东西 */
    public String summarize(Report report) {
        Map<String, Object> flat = new LinkedHashMap<>();
        for (Check check : report.checks()) {
            flat.put(check.name(), check.status());
        }
        return report.healthy()
                ? "启动自检通过：" + flat
                : "启动自检有 " + report.failed() + " 项失败 / " + report.warned() + " 项提醒：" + flat;
    }

    /** 供只读诊断接口复用：把结论摊平成 JSON 友好的形状 */
    public List<Map<String, Object>> checksAsMaps() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Check check : last().checks()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", check.name());
            row.put("status", check.status());
            row.put("detail", Masking.scrubText(check.detail()));
            out.add(row);
        }
        return out;
    }
}
