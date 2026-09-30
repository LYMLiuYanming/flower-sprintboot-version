package org.liuym.flowerv1springboot.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 调度任务的观测装饰器（L10）：把每个 @Scheduled 执行包一层，交给 {@link ScheduledJobTracker} 记账。
 *
 * <p>做法是给调度线程池装 TaskDecorator，而不是改任务类或引 AOP（本批不允许加 aspectjweaver）。
 * 好处是零侵入、新任务自动纳入；代价是拿不到方法返回值，所以「处理条数」需要任务自己 report()。
 *
 * <p>任务名取法：{@code ScheduledMethodRunnable} 能直接给出类与方法；
 * 被 Spring 的错误处理包装过时只能退化成解析 toString()，再不行记为 anonymous。
 */
@Component
public class ScheduledJobCollector implements TaskDecorator {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobCollector.class);
    private static final Pattern METHOD_TEXT = Pattern.compile("([\\w$.]+)\\.([\\w$]+)\\s*\\(");

    private final ScheduledJobTracker tracker;
    private final AtomicBoolean attached = new AtomicBoolean(false);

    public ScheduledJobCollector(ScheduledJobTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public Runnable decorate(Runnable task) {
        String job = jobName(task);
        return () -> {
            ScheduledJobTracker.Context context = tracker.start(job);
            Throwable failure = null;
            try {
                task.run();
            } catch (RuntimeException | Error e) {
                // 原样抛出：调度线程池自带的 ErrorHandler 还要据此打堆栈，我们只是路过记一笔
                failure = e;
                throw e;
            } finally {
                try {
                    tracker.finish(context, failure);
                } catch (RuntimeException e) {
                    log.warn("任务台账写入失败 {}: {}", job, e.getClass().getSimpleName());
                }
            }
        };
    }

    /** 由装配代码在成功挂到线程池后调用，健康检查据此回答「台账是不是真的在采」 */
    public void markAttached() {
        attached.set(true);
    }

    public boolean attached() {
        return attached.get();
    }

    /** 采到的任务数：为 0 且已挂上，说明调度器还没跑过任何任务 */
    public int trackedJobs() {
        return tracker.snapshot().size();
    }

    private static String jobName(Runnable task) {
        if (task instanceof ScheduledMethodRunnable method) {
            Object target = method.getTarget();
            String clazz = target instanceof Class<?> type ? type.getSimpleName()
                    : (target == null ? "?" : target.getClass().getSimpleName());
            return clazz + "." + method.getMethod().getName();
        }
        String text = task.toString();
        if (text != null) {
            Matcher matcher = METHOD_TEXT.matcher(text);
            if (matcher.find()) {
                String clazz = matcher.group(1);
                int dot = clazz.lastIndexOf('.');
                return (dot >= 0 ? clazz.substring(dot + 1) : clazz) + "." + matcher.group(2);
            }
        }
        return "anonymousScheduledTask";
    }
}
