package org.liuym.flowerv1springboot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 触达专用的异步执行器（U02）。
 *
 * <p>刻意<em>不</em>加 {@code @EnableAsync}：全局异步代理会影响其它并行施工批次已有的
 * {@code @Async}（若有）语义，而这里只需要「把落库这件事挪出主事务之外」。
 * 因此监听器直接持有这个 Executor 提交任务，效果与 {@code @Async} 相同、影响面为零。
 *
 * <p>拒绝策略用 CallerRuns：队列打满说明事件产生速度长期超过单线程消费能力，
 * 此时让发布线程自己跑一次（仍在主事务之外）比丢消息好，同时天然形成背压。
 */
@Configuration
public class NotificationAsyncConfig {

    /** 触达线程池的 bean 名：监听器按名字注入，别处不要复用这个池 */
    public static final String EXECUTOR = "notificationTaskExecutor";

    @Bean(name = EXECUTOR)
    public ThreadPoolTaskExecutor notificationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("notify-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅停机时等已提交的触达任务落库完，避免角标数字与库里条数差一两条
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
