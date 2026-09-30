package org.liuym.flowerv1springboot.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.ErrorCode;
import org.liuym.flowerv1springboot.common.TokenBucketRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 关键接口限流（L04）：只对「能被脚本刷爆」的少数端点计数，其余接口一律不管。
 *
 * <p>刻意做成拦截器而不是过滤器：路径匹配交给 Spring MVC 的 pattern，能与 @RequestMapping 的实际形状对齐，
 * 不必在这里手写一堆 startsWith 猜业务路由。
 *
 * <p>被拒的请求在本拦截器直接写回 429 + Retry-After，不进 Controller，也就不会占用连接与事务。
 * 全局开关 {@code app.rate-limit.enabled} 关掉后完全直通，作为「误伤线上业务」的逃生阀。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    /** 计数维度 */
    public enum Scope {
        /** 按来源 IP：登录、图形码这类「匿名也能发起」的入口 */
        IP,
        /** 按登录用户：下单、领券、上传，未登录时退回 IP 维度，绝不给匿名请求开后门 */
        USER
    }

    /**
     * 一条规则。
     *
     * @param name     规则名，出现在 key 与日志里，改名等于换桶（旧额度作废）
     * @param pattern  匹配的路径模式（Ant 风格，由本类简化匹配，不用 PathMatcher 是因为规则量很小）
     * @param scope    计数维度
     * @param capacity 突发上限
     * @param refill   稳态每秒补充数
     * @param message  被拒时给用户的可读文案
     */
    public record Rule(String name, String pattern, Scope scope, int capacity, double refill, String message) {
    }

    private final TokenBucketRateLimiter limiter;
    private final List<Rule> rules;
    private final boolean enabled;
    private final boolean trustForwardedFor;

    public RateLimitInterceptor(
            TokenBucketRateLimiter limiter,
            @Value("${app.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor,
            @Value("${app.rate-limit.login.capacity:10}") int loginCapacity,
            @Value("${app.rate-limit.login.refill-per-second:0.2}") double loginRefill,
            @Value("${app.rate-limit.order.capacity:6}") int orderCapacity,
            @Value("${app.rate-limit.order.refill-per-second:0.1}") double orderRefill,
            @Value("${app.rate-limit.claim.capacity:5}") int claimCapacity,
            @Value("${app.rate-limit.claim.refill-per-second:0.08}") double claimRefill,
            @Value("${app.rate-limit.captcha.capacity:30}") int captchaCapacity,
            @Value("${app.rate-limit.captcha.refill-per-second:1}") double captchaRefill,
            @Value("${app.rate-limit.upload.capacity:12}") int uploadCapacity,
            @Value("${app.rate-limit.upload.refill-per-second:0.2}") double uploadRefill) {
        this.limiter = limiter;
        this.enabled = enabled;
        this.trustForwardedFor = trustForwardedFor;
        this.rules = new ArrayList<>();
        // 登录：capacity 10 / refill 0.2 ⇒ 稳态 5 次/分钟。真人不会一分钟登 5 次，
        // 而撞库脚本一次就打满，之后每分钟只放 12 次，配合 LoginAttemptService 的账号锁定形成双层
        add("login", "/auth/login", Scope.IP, loginCapacity, loginRefill, ErrorCode.LOGIN_TOO_MANY.message());
        add("login-api", "/auth/api/login", Scope.IP, loginCapacity, loginRefill, ErrorCode.LOGIN_TOO_MANY.message());
        add("register", "/auth/api/register", Scope.IP, Math.max(2, loginCapacity / 2), loginRefill / 2,
                ErrorCode.TOO_MANY_REQUESTS.message());
        // 图形码：给到 30 突发 + 每秒 1，刷新页面连点不会误伤，刷码脚本会被卡住
        add("captcha", "/api/auth/captcha", Scope.IP, captchaCapacity, captchaRefill,
                ErrorCode.TOO_MANY_REQUESTS.message());
        // 下单：6 突发 + 每 10 秒 1。正常用户提交一次订单后要跳支付页，不可能连发 6 次
        add("order-create", "/api/orders/create", Scope.USER, orderCapacity, orderRefill,
                ErrorCode.ORDER_TOO_MANY.message());
        add("order-pay", "/api/orders/*/pay", Scope.USER, orderCapacity, orderRefill,
                ErrorCode.ORDER_TOO_MANY.message());
        add("order-refund", "/api/orders/*/refund", Scope.USER, Math.max(3, orderCapacity / 2), orderRefill,
                ErrorCode.ORDER_TOO_MANY.message());
        // 领券：5 突发 + 每 12 秒 1 张。券是资金，宁可慢也不能被脚本秒空
        add("coupon-claim", "/api/coupons/claim", Scope.USER, claimCapacity, claimRefill,
                ErrorCode.CLAIM_TOO_MANY.message());
        add("coupon-transfer-accept", "/api/coupons/transfer/accept", Scope.USER, claimCapacity, claimRefill,
                ErrorCode.CLAIM_TOO_MANY.message());
        // 上传：12 突发 + 每 5 秒 1 张，覆盖「晒单一口气传 9 张图」的正常场景
        add("upload", "/api/reviews/uploads/image", Scope.USER, uploadCapacity, uploadRefill,
                ErrorCode.UPLOAD_TOO_MANY.message());
    }

    private void add(String name, String pattern, Scope scope, int capacity, double refill, String message) {
        rules.add(new Rule(name, pattern, scope, capacity, refill, message));
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!enabled || !"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = pathOf(request);
        for (Rule rule : rules) {
            if (!matches(rule.pattern(), path)) {
                continue;
            }
            String key = rule.name() + ':' + principalOf(request, rule.scope());
            var decision = limiter.tryAcquire(key, rule.capacity(), rule.refill());
            if (decision.allowed()) {
                return true;
            }
            // 只 WARN 不 ERROR：这是预期内的防护动作，不该在告警里冒充故障
            log.warn("限流拦截 {} {} key={} retryAfter={}s", request.getMethod(), path, key,
                    decision.retryAfterSeconds());
            writeTooManyRequests(response, rule, decision.retryAfterSeconds());
            return false;
        }
        return true;
    }

    /** 拒绝响应：HTTP 429 + Retry-After，body 沿用 Result 协议，前端老代码按 body.code 判断也能走通 */
    private void writeTooManyRequests(HttpServletResponse response, Rule rule, long retryAfter) throws Exception {
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":429,\"msg\":\"" + rule.message()
                + "（约 " + retryAfter + " 秒后可重试）\"}");
    }

    /** 计数主体：USER 维度优先用登录用户 id，未登录退回 IP，避免匿名请求绕过额度 */
    private String principalOf(HttpServletRequest request, Scope scope) {
        if (scope == Scope.USER) {
            // getSession(false)：拦截器在最前面跑，不该为了取身份凭空建一个会话
            jakarta.servlet.http.HttpSession session = request.getSession(false);
            var user = session == null ? null : CurrentUser.of(session);
            if (user != null) {
                return "u" + user.getId();
            }
        }
        return "ip" + clientIp(request);
    }

    private String clientIp(HttpServletRequest request) {
        if (trustForwardedFor) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        // 默认不认 XFF：这个头任何人都能伪造，认了等于把限流变成「换个头就能刷」
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }

    private String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        return uri.toLowerCase(Locale.ROOT);
    }

    /** 只支持整段通配（形如 /api/orders/{id}/pay），够用且不必引入 AntPathMatcher */
    static boolean matches(String pattern, String path) {
        String[] want = pattern.split("/");
        String[] got = path.split("/");
        if (want.length != got.length) {
            return false;
        }
        for (int i = 0; i < want.length; i++) {
            if (want[i].equals("*")) {
                continue;
            }
            if (!want[i].equals(got[i])) {
                return false;
            }
        }
        return true;
    }

    /** 诊断用：当前生效的规则表（含阈值），回答「为什么这里会 429」 */
    public List<Rule> rules() {
        return List.copyOf(rules);
    }

    public boolean enabled() {
        return enabled;
    }

    public long trackedKeys() {
        return limiter.trackedKeys();
    }
}
