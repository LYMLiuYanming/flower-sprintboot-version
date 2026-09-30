package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.common.Masking;
import org.liuym.flowerv1springboot.common.NotificationEvent;
import org.liuym.flowerv1springboot.config.NotificationAsyncConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 触达事件监听器（U02）。
 *
 * <p>两条约束决定了这里的写法：
 * <ol>
 *   <li><b>AFTER_COMMIT</b>：站内信描述的是「已经发生的事实」。若在事务内落库，
 *       订单事务回滚后顾客会收到一条指向不存在订单的通知；</li>
 *   <li><b>提交到独立线程池</b>：与 {@code @Async} 等效，但不需要为一条旁路能力打开全局异步代理。
 *       任务体内任何异常都在此吞掉并打 WARN——触达失败只告警，绝不冒泡回业务线程
 *       （与第一轮 {@code OrderServiceImpl#trace} 的容错口径一致）。</li>
 * </ol>
 *
 * <p>{@code fallbackExecution = true}：定时任务与后台群发是在事务外发布事件的，
 * 没有这个开关它们会全部被忽略。
 */
@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final MessageDispatchService dispatchService;
    private final ThreadPoolTaskExecutor executor;

    public NotificationEventListener(MessageDispatchService dispatchService,
                                     @Qualifier(NotificationAsyncConfig.EXECUTOR) ThreadPoolTaskExecutor executor) {
        this.dispatchService = dispatchService;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNotificationEvent(NotificationEvent event) {
        try {
            executor.execute(() -> {
                try {
                    dispatchService.dispatch(event);
                } catch (RuntimeException e) {
                    // 只打类型与一句话摘要：异常消息里常带着手机号，日志不能成为第二个泄露点
                    log.warn("站内信落库失败 type={} bizId={}: {}",
                            event.bizType(), event.bizId(), Masking.brief(e));
                }
            });
        } catch (RuntimeException e) {
            log.warn("触达任务提交失败 type={} bizId={}: {}", event.bizType(), event.bizId(), Masking.brief(e));
        }
    }
}
