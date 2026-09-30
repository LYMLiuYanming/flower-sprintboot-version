package org.liuym.flowerv1springboot.config;

import org.liuym.flowerv1springboot.common.ScheduledJobCollector;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 把任务台账挂到调度线程池上（L10）。
 *
 * <p>必须是 BeanPostProcessor 且在初始化**之前**注入：ThreadPoolTaskScheduler 一旦 afterPropertiesSet
 * 建好线程池就再也不会读 taskDecorator 字段。用普通 @Bean 替换整个 TaskScheduler 也能做到，
 * 但那样会把 spring.task.scheduling.* 的池大小/线程名配置一并接管过来，不值当。
 *
 * <p>ObjectProvider 延迟取用：BPP 本身在容器极早期实例化，此时直接依赖注入会把
 * ScheduledJobCollector 一起拖进「早于正常时序」的创建队列里。
 */
@Configuration
public class ScheduledJobCollectorConfig {

    @Bean
    public static BeanPostProcessor scheduledJobTaskDecoratorPostProcessor(ObjectProvider<ScheduledJobCollector> provider) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof ThreadPoolTaskScheduler scheduler) {
                    ScheduledJobCollector collector = provider.getIfAvailable();
                    if (collector != null) {
                        scheduler.setTaskDecorator(collector);
                        collector.markAttached();
                    }
                }
                return bean;
            }
        };
    }
}
