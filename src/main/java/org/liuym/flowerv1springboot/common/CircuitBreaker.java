package org.liuym.flowerv1springboot.common;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * 轻量熔断器（L07）：连续失败到达阈值后，一段时间内**直接走降级、不再打外网**。
 *
 * <p>存在的理由：高德配额按调用次数计费，外部服务挂掉时如果每个请求都照打，
 * 等于在最没结果的事情上持续烧配额，还把 5 秒超时叠加到用户面前。
 *
 * <p>阈值取「连续 5 次失败 → 断路 60 秒」：单张地图页一次会发起 1~2 次外呼，
 * 5 次连续失败已经覆盖 2~3 个页面的访问量，再往下限就属于过敏；
 * 60 秒与 Caffeine 的天气缓存同量级，恢复后第一批请求会自然把结果重新填满。
 *
 * <p>只统计「网络/HTTP/业务失败」，不统计「查不到数据」——地址取不到点是数据问题，不是依赖故障。
 * 半开状态下只放一个探针，避免恢复瞬间被积压请求打穿。
 */
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    /**
     * @param state              当前状态
     * @param consecutiveFailures 连续失败次数（成功即归零）
     * @param totalFailures      累计失败
     * @param totalBlocked       因断路被直接降级掉的调用数——这个数就是「省下的配额」
     * @param trips              累计熔断次数（打开过多少次）
     * @param openRemainingMs    距离允许下一次探测还剩多久，CLOSED 时为 0
     */
    public record Snapshot(State state, int consecutiveFailures, long totalFailures, long totalBlocked,
                           long trips, long openRemainingMs) {

        public boolean degraded() {
            return state != State.CLOSED;
        }
    }

    private final int failureThreshold;
    private final long openNanos;
    private final LongSupplier clock;

    private int consecutiveFailures;
    private long totalFailures;
    private long totalBlocked;
    private long trips;
    /** 断路窗口结束的时刻（纳秒时钟基准）；0 表示当前没有打开 */
    private long openUntil;
    /** 半开探针是否已被占用：保证恢复瞬间只有一个请求真去试外网 */
    private boolean probeInFlight;

    public CircuitBreaker(int failureThreshold, Duration openWindow) {
        this(failureThreshold, openWindow, System::nanoTime);
    }

    public CircuitBreaker(int failureThreshold, Duration openWindow, LongSupplier clock) {
        this.failureThreshold = Math.max(2, failureThreshold);
        this.openNanos = Math.max(1_000_000L, openWindow.toNanos());
        this.clock = clock;
    }

    /**
     * 本次调用是否允许出门。
     * 返回 false 时调用方必须自己降级（并**不要**再调 {@link #recordFailure}——被挡下的不算失败）。
     */
    public synchronized boolean allowRequest() {
        long now = clock.getAsLong();
        if (openUntil == 0) {
            return true;
        }
        if (now < openUntil) {
            totalBlocked++;
            return false;
        }
        // 窗口到期：放一个探针出去，其余请求继续降级
        if (probeInFlight) {
            totalBlocked++;
            return false;
        }
        probeInFlight = true;
        return true;
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        openUntil = 0;
        probeInFlight = false;
    }

    /** 记一次失败；达到阈值即打开断路窗口 */
    public synchronized void recordFailure() {
        totalFailures++;
        consecutiveFailures++;
        probeInFlight = false;
        if (consecutiveFailures >= failureThreshold) {
            if (openUntil == 0 || clock.getAsLong() >= openUntil) {
                trips++;
            }
            openUntil = clock.getAsLong() + openNanos;
        }
    }

    /** 探针失败：立刻重新拉开窗口，别让每个后续请求都轮流的试一次 */
    public synchronized void openNow() {
        totalFailures++;
        consecutiveFailures++;
        probeInFlight = false;
        trips++;
        openUntil = clock.getAsLong() + openNanos;
    }

    public synchronized State state() {
        if (openUntil == 0) {
            return State.CLOSED;
        }
        return clock.getAsLong() < openUntil ? State.OPEN : State.HALF_OPEN;
    }

    public synchronized Snapshot snapshot() {
        long remaining = openUntil == 0 ? 0 : Math.max(0, (openUntil - clock.getAsLong()) / 1_000_000L);
        return new Snapshot(state(), consecutiveFailures, totalFailures, totalBlocked, trips, remaining);
    }

    /** 手工复位（演练或误判时用） */
    public synchronized void reset() {
        consecutiveFailures = 0;
        openUntil = 0;
        probeInFlight = false;
    }

    public int failureThreshold() {
        return failureThreshold;
    }

    public long openWindowSeconds() {
        return Math.max(1, openNanos / 1_000_000_000L);
    }
}
