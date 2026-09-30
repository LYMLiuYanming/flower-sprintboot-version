package org.liuym.flowerv1springboot.task;

import org.liuym.flowerv1springboot.common.Masking;
import org.liuym.flowerv1springboot.common.ScheduledJobTracker;
import org.liuym.flowerv1springboot.service.MessageDispatchService;
import org.liuym.flowerv1springboot.vo.NotificationViews;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 消息中心的两个定时出口（U21 到点投递 / U10 保留期清理）。
 *
 * <p>台账不用自己写：调度线程池装了 {@code ScheduledJobCollector} 装饰器，
 * 任何 @Scheduled 方法都会自动进 {@link ScheduledJobTracker}，这里只额外上报「本轮处理条数」，
 * 后台才能回答「这一轮到底投出去几条、删掉几条」而不只是一个耗时数字。
 *
 * <p>两个任务都可以放心重复触发：投递走 {@code pending→sending} 的条件 UPDATE 抢占（U21），
 * 清理走按 cutoff 的整批删除 + 归档计数的 ON CONFLICT 合并，重入都不会双发或重复归档。
 */
@Component
public class ScheduledMessageTasks {

    private static final Logger log = LoggerFactory.getLogger(ScheduledMessageTasks.class);

    private final MessageDispatchService dispatchService;
    private final ScheduledJobTracker tracker;
    private final int batchLimit;

    public ScheduledMessageTasks(MessageDispatchService dispatchService,
                                 ScheduledJobTracker tracker,
                                 @Value("${app.message.scheduled-batch-limit:200}") int batchLimit) {
        this.dispatchService = dispatchService;
        this.tracker = tracker;
        this.batchLimit = Math.max(1, Math.min(batchLimit, 500));
    }

    /**
     * U21：把到点的延时消息投进站内信。
     *
     * <p>频率比订单扫描低一档（默认 30 秒）：养护提醒的粒度是天，秒级抢跑没有意义，
     * 但免打扰顺延的那批要在窗口结束后尽快补上，所以也不能按分钟算。
     */
    @Scheduled(fixedDelayString = "${app.message.deliver-sweep-interval-ms:30000}",
            initialDelayString = "${app.message.deliver-initial-delay-ms:20000}")
    public void deliverDueScheduled() {
        try {
            int delivered = dispatchService.deliverDueScheduled(batchLimit);
            tracker.report(delivered);
            if (delivered > 0) {
                log.info("延时消息到点投递 {} 条", delivered);
            }
        } catch (RuntimeException e) {
            // 摘要里不带异常原文：消息正文与手机号可能出现在持久化异常的参数回显里
            log.warn("延时消息投递任务失败：{}", Masking.brief(e));
            throw e;
        }
    }

    /** U10：保留期到期的消息先按「用户 + 月份 + 类别」归档计数，再物理删除 */
    @Scheduled(cron = "${app.message.retention-cron:0 15 4 * * *}")
    public void cleanupExpiredMessages() {
        try {
            NotificationViews.RetentionResult result = dispatchService.cleanupExpiredMessages();
            tracker.report(result.deletedCount());
            log.info("消息保留期清理：{} 天口径，删除 {} 条，归档 {} 个分桶",
                    result.retentionDays(), result.deletedCount(), result.archivedBuckets());
        } catch (RuntimeException e) {
            log.warn("消息保留期清理任务失败：{}", Masking.brief(e));
            throw e;
        }
    }
}
