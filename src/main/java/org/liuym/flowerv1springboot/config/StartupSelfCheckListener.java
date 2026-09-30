package org.liuym.flowerv1springboot.config;

import org.liuym.flowerv1springboot.common.SystemHealthProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * 启动自检触发器（L15）：应用真正可服务之后跑一遍探测，把结论写进日志与快照。
 *
 * <p>为什么用 ApplicationReadyEvent 而不是 @PostConstruct：@PostConstruct 跑在容器还没起完的时候，
 * 那时连接池与调度线程都可能是半成品，探出来的「失败」是假故障。
 *
 * <p>这里<b>绝不抛异常</b>：自检是附加信息，不能把一次本可以正常服务的启动变成回不去的崩溃。
 */
@Component
public class StartupSelfCheckListener implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupSelfCheckListener.class);

    private final SystemHealthProbe probe;
    private final boolean enabled;

    public StartupSelfCheckListener(SystemHealthProbe probe,
                                    @Value("${app.self-check.enabled:true}") boolean enabled) {
        this.probe = probe;
        this.enabled = enabled;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!enabled) {
            log.info("启动自检已关闭（app.self-check.enabled=false）");
            return;
        }
        try {
            SystemHealthProbe.Report report = probe.check();
            String summary = probe.summarize(report);
            if (report.healthy() && report.warned() == 0) {
                log.info("启动自检 {}", summary);
            } else {
                // 统一 WARN：失败项靠日志暴露给运维，但不影响进程存活（约束里写明的「不崩启动」）
                log.warn("启动自检 {}", summary);
                report.checks().stream()
                        .filter(check -> !"ok".equals(check.status()))
                        .forEach(check -> log.warn("  · {} [{}] {}", check.label(), check.status(), check.detail()));
            }
        } catch (RuntimeException e) {
            log.warn("启动自检本身执行失败，跳过：{}", e.getClass().getSimpleName());
        }
    }
}
