package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.CareContentPolicy;
import org.liuym.flowerv1springboot.common.Masking;
import org.liuym.flowerv1springboot.common.MessageKeys;
import org.liuym.flowerv1springboot.common.MessagePublisher;
import org.liuym.flowerv1springboot.common.MessageRenderPolicy;
import org.liuym.flowerv1springboot.common.MessageTargetPolicy;
import org.liuym.flowerv1springboot.common.NotificationEvent;
import org.liuym.flowerv1springboot.common.NotificationPolicy;
import org.liuym.flowerv1springboot.dto.NotificationDtos;
import org.liuym.flowerv1springboot.model.BroadcastCampaign;
import org.liuym.flowerv1springboot.model.MessageArchiveStat;
import org.liuym.flowerv1springboot.model.MessageGuardEvent;
import org.liuym.flowerv1springboot.model.MessageTemplate;
import org.liuym.flowerv1springboot.model.ScheduledMessage;
import org.liuym.flowerv1springboot.model.UserMessage;
import org.liuym.flowerv1springboot.repository.BroadcastCampaignRepository;
import org.liuym.flowerv1springboot.repository.MessageArchiveStatRepository;
import org.liuym.flowerv1springboot.repository.MessageGuardEventRepository;
import org.liuym.flowerv1springboot.repository.MessageTemplateRepository;
import org.liuym.flowerv1springboot.repository.NotificationPrefRepository;
import org.liuym.flowerv1springboot.repository.NotificationProfileRepository;
import org.liuym.flowerv1springboot.repository.NotificationSourceRepository;
import org.liuym.flowerv1springboot.repository.ScheduledMessageRepository;
import org.liuym.flowerv1springboot.repository.UserMessageRepository;
import org.liuym.flowerv1springboot.repository.UserRepository;
import org.liuym.flowerv1springboot.service.MessageDispatchService;
import org.liuym.flowerv1springboot.vo.NotificationViews;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 触达引擎实现。
 *
 * <p>一条消息的固定路径：收件人有效 → 模板渲染（缺槽位整条不落库）→ 跳转地址白名单 →
 * 偏好与免打扰判定 → 幂等落库。每步返回结果码，失败原因写进返回值而不是只留在日志里。
 */
@Service
@Transactional
public class MessageDispatchServiceImpl implements MessageDispatchService {

    private static final Logger log = LoggerFactory.getLogger(MessageDispatchServiceImpl.class);

    /** 单次群发的人群上限：本机演示数据用不到，写出来是为了「超过就分批」而不是让一个事务吃掉整库 */
    static final int BROADCAST_MAX_AUDIENCE = 5000;

    /** 抢占租约（分钟）：进程在 claim 之后崩溃，行会停在 sending，超过租约才允许被重新抢走 */
    static final int CLAIM_LEASE_MINUTES = 5;

    /** 重试间隔（分钟）：失败后按 attempts 线性推后，避免坏数据每轮都撞一次 */
    static final int RETRY_STEP_MINUTES = 10;

    /** 营销类事件：才受周末停发与每日上限约束 */
    private static final java.util.Set<String> MARKETING_EVENTS = java.util.Set.of(
            MessagePublisher.EVENT_COUPON_EXPIRING, MessagePublisher.EVENT_CARD_EXPIRING,
            MessagePublisher.EVENT_CARD_TRANSFERRED, MessagePublisher.EVENT_BROADCAST);

    /** 工单类事件：受偏好页的 ticket 开关约束 */
    private static final java.util.Set<String> TICKET_EVENTS = java.util.Set.of(
            MessagePublisher.EVENT_TICKET_REPLIED, MessagePublisher.EVENT_TICKET_RESOLVED);

    /** 交易类事件：允许穿透免打扰 */
    private static final java.util.Set<String> TRADE_EVENTS = java.util.Set.of(
            MessagePublisher.EVENT_ORDER_PAID, MessagePublisher.EVENT_ORDER_SHIPPED,
            MessagePublisher.EVENT_ORDER_DELIVERED, MessagePublisher.EVENT_REFUND_RESULT,
            MessagePublisher.EVENT_SUBSCRIPTION_DUE);

    private final UserMessageRepository messageRepository;
    private final ScheduledMessageRepository scheduledRepository;
    private final MessageTemplateRepository templateRepository;
    private final NotificationPrefRepository prefRepository;
    private final NotificationProfileRepository profileRepository;
    private final MessageGuardEventRepository guardRepository;
    private final BroadcastCampaignRepository campaignRepository;
    private final MessageArchiveStatRepository archiveRepository;
    private final NotificationSourceRepository sourceRepository;
    private final UserRepository userRepository;

    /** U10 保留天数：默认 90 天，配置项 app.message.retention-days 可改 */
    private final int retentionDays;

    public MessageDispatchServiceImpl(UserMessageRepository messageRepository,
                                      ScheduledMessageRepository scheduledRepository,
                                      MessageTemplateRepository templateRepository,
                                      NotificationPrefRepository prefRepository,
                                      NotificationProfileRepository profileRepository,
                                      MessageGuardEventRepository guardRepository,
                                      BroadcastCampaignRepository campaignRepository,
                                      MessageArchiveStatRepository archiveRepository,
                                      NotificationSourceRepository sourceRepository,
                                      UserRepository userRepository,
                                      @Value("${app.message.retention-days:90}") int retentionDays) {
        this.messageRepository = messageRepository;
        this.scheduledRepository = scheduledRepository;
        this.templateRepository = templateRepository;
        this.prefRepository = prefRepository;
        this.profileRepository = profileRepository;
        this.guardRepository = guardRepository;
        this.campaignRepository = campaignRepository;
        this.archiveRepository = archiveRepository;
        this.sourceRepository = sourceRepository;
        this.userRepository = userRepository;
        this.retentionDays = Math.max(7, retentionDays);
    }

    @Override
    public DispatchResult dispatch(NotificationEvent event) {
        if (event == null || event.userId() == null) {
            return DispatchResult.of(Outcome.INVALID, "事件缺少收件人");
        }
        String bizType = event.qualifier() == null
                ? event.bizType()
                : event.bizType();
        if (bizType == null || bizType.isBlank()) {
            return DispatchResult.of(Outcome.INVALID, "事件缺少业务类型");
        }
        String category = categoryOf(event);
        if (!userRepository.existsById(event.userId())) {
            // 注销账号的 id 仍留在业务表里：消息发出去没人能看到，落库只会变成垃圾行
            return DispatchResult.of(Outcome.INVALID, "收件人账号不存在");
        }

        Rendered rendered = render(event);
        if (!rendered.ok()) {
            return DispatchResult.of(Outcome.INVALID, rendered.reason());
        }
        String linkUrl = safeLink(event.linkUrl(), bizType);
        String dedupKey = MessageKeys.of(bizType, event.bizId(), event.userId(), event.qualifier());

        LocalDateTime now = LocalDateTime.now();
        if (event.dueAt() != null && event.dueAt().isAfter(now)) {
            return schedule(event, category, rendered, linkUrl, dedupKey, event.dueAt(), now);
        }

        NotificationPolicy.Decision decision = decide(event.userId(), category, now);
        if (decision.action() == NotificationPolicy.Action.SUPPRESS) {
            return DispatchResult.suppressed(decision.note(), decision.reason());
        }
        if (decision.action() == NotificationPolicy.Action.DEFER) {
            return schedule(event, category, rendered, linkUrl, dedupKey, decision.at(), now);
        }

        int rows = insertMessage(event.userId(), category, rendered, linkUrl, bizType, dedupKey, now);
        return rows > 0 ? DispatchResult.delivered(rows)
                : DispatchResult.of(Outcome.DUPLICATE, "同一业务事件已触达过，重复请求被幂等键拦下");
    }

    /** 落一行站内信：ON CONFLICT DO NOTHING，返回 0 即这一条已经存在 */
    private int insertMessage(UUID userId, String category, Rendered rendered, String linkUrl,
                              String bizType, String dedupKey, LocalDateTime now) {
        return messageRepository.insertIfAbsent(UUID.randomUUID(), userId, category, rendered.title(),
                rendered.content(), linkUrl, bizType, rendered.bizId(), rendered.templateCode(), dedupKey);
    }

    private DispatchResult schedule(NotificationEvent event, String category, Rendered rendered, String linkUrl,
                                    String dedupKey, LocalDateTime dueAt, LocalDateTime now) {
        LocalDateTime effective = dueAt.isBefore(now) ? now.plusMinutes(1) : dueAt;
        int rows = scheduledRepository.insertIfAbsent(UUID.randomUUID(), event.userId(), category,
                event.templateCode(), rendered.title(), rendered.content(), linkUrl, rendered.payload(),
                effective, dedupKey, event.bizType(), String.valueOf(event.bizId()),
                event.customised() == null || event.customised());
        return rows > 0
                ? new DispatchResult(Outcome.SCHEDULED, null, rows, NotificationPolicy.Reason.NONE)
                : DispatchResult.of(Outcome.DUPLICATE, "同一业务事件的延时消息已排期");
    }

    /** U21：抢占 → 落库 → 结论回写，全程条件 UPDATE，多副本/重启/任务重入都不会重发 */
    @Override
    public int deliverDueScheduled(int batchLimit) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseExpired = now.minusMinutes(CLAIM_LEASE_MINUTES);
        List<ScheduledMessage> due = scheduledRepository.findDue(now, leaseExpired,
                PageRequest.of(0, Math.max(1, Math.min(batchLimit, 500))));
        int delivered = 0;
        for (ScheduledMessage message : due) {
            // claim 带 clearAutomatically，实体之后是脱管的：要用的字段先全部取成局部变量
            UUID id = message.getId();
            UUID userId = message.getUserId();
            String category = message.getCategory();
            String title = message.getTitle();
            String content = message.getContent();
            String linkUrl = message.getLinkUrl();
            String templateCode = message.getTemplateCode();
            String dedupKey = message.getDedupKey();
            String bizType = message.getBizType();
            String bizId = message.getBizId();

            if (scheduledRepository.claim(id, now, leaseExpired) == 0) {
                // 影响行数 0：这一条已被别的线程或别的轮次拿走，跳过就是正确行为
                continue;
            }
            try {
                int rows = messageRepository.insertIfAbsent(UUID.randomUUID(), userId, category, title, content,
                        linkUrl, bizType, bizId, templateCode, dedupKey);
                scheduledRepository.markSent(id, LocalDateTime.now());
                // rows=0 是重入撞了 dedup_key：消息早已存在，这里只把排期行收尾，不再重复计数
                delivered += rows;
            } catch (RuntimeException e) {
                String error = Masking.brief(e);
                LocalDateTime retryAt = LocalDateTime.now().plusMinutes((long) RETRY_STEP_MINUTES
                        * Math.max(1, message.getAttempts() == null ? 1 : message.getAttempts()));
                if (scheduledRepository.markRetry(id, LocalDateTime.now(), retryAt, error) == 0) {
                    scheduledRepository.markFailed(id, LocalDateTime.now(), error);
                }
                log.warn("延时消息投递失败 id={}：{}", id, error);
            }
        }
        return delivered;
    }

    /** U20/U22：第 2/4/7 天养护提醒，文案按花材命中第一轮 F15 知识库 */
    @Override
    public int scheduleCareReminders(UUID userId, UUID orderId, String orderNo, LocalDateTime deliveredAt) {
        if (userId == null || orderId == null || deliveredAt == null) {
            return 0;
        }
        List<NotificationSourceRepository.OrderMaterial> materials = sourceRepository.findOrderMaterials(orderId);
        List<NotificationSourceRepository.CareArticle> articles = sourceRepository.findCareArticles(60);
        String materialText = materials.stream()
                .map(NotificationSourceRepository.OrderMaterial::material)
                .filter(m -> m != null && !m.isBlank())
                .distinct()
                .reduce((a, b) -> a + "、" + b)
                .orElse("");
        String productText = materials.stream()
                .map(NotificationSourceRepository.OrderMaterial::productName)
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .reduce((a, b) -> a + "、" + b)
                .orElse("");
        NotificationSourceRepository.CareArticle matched = articles.stream()
                .filter(a -> CareContentPolicy.matches(materialText, a.materials()))
                .findFirst()
                .orElse(null);

        int created = 0;
        for (int index = 0; index < CareContentPolicy.REMINDER_DAYS.length; index++) {
            CareContentPolicy.CareContent care = CareContentPolicy.build(index, materialText, productText,
                    matched == null ? null : matched.title(), matched == null ? null : matched.summary());
            int day = CareContentPolicy.REMINDER_DAYS[index];
            String title = "养护提醒 · 第 " + day + " 天：" + (orderNo == null ? "您的花礼" : "订单 " + orderNo);
            int rows = scheduledRepository.insertIfAbsent(UUID.randomUUID(), userId, UserMessage.CATEGORY_SYSTEM,
                    MessagePublisher.EVENT_CARE_REMINDER, truncate(title, 200), care.content(), "/user/orders",
                    carePayload(orderNo, materialText, matched),
                    CareContentPolicy.dueAtFor(deliveredAt, index),
                    // 幂等键带上天数：同一订单的三条提醒互不冲突，重扫又不会产生第四天
                    MessageKeys.of(MessagePublisher.EVENT_CARE_REMINDER, orderId, userId, day),
                    MessagePublisher.EVENT_CARE_REMINDER, String.valueOf(orderId), care.customised());
            created += rows;
        }
        return created;
    }

    /** U10：先聚合归档计数，再物理删除，删除条数交给调用方进任务台账 */
    @Override
    public NotificationViews.RetentionResult cleanupExpiredMessages() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        List<Object[]> buckets = messageRepository.aggregateBefore(cutoff);
        LocalDateTime now = LocalDateTime.now();
        List<MessageArchiveStat> stats = buckets.stream().map(row -> {
            MessageArchiveStat stat = new MessageArchiveStat();
            stat.setUserId((UUID) row[0]);
            stat.setBucketMonth(String.valueOf(row[1]));
            stat.setCategory(String.valueOf(row[2]));
            stat.setMessageCount(((Number) row[3]).intValue());
            stat.setArchivedAt(now);
            return stat;
        }).toList();
        if (!stats.isEmpty()) {
            archiveRepository.saveAll(stats);
        }
        int deleted = messageRepository.deleteCreatedBefore(cutoff);
        return new NotificationViews.RetentionResult(retentionDays, deleted, stats.size(), cutoff,
                deleted == 0 ? "本轮没有超过保留期的消息" : "已按「用户 + 月份 + 类别」归档计数后物理删除");
    }

    /** U23：群发。dryRun 走同一套判定，只是不写任何行 */
    @Override
    public NotificationViews.BroadcastResult broadcast(NotificationDtos.BroadcastForm form,
                                                      UUID operatorId, String operatorName) {
        String segment = normalizeSegment(form.segmentCode());
        int windowDays = windowDaysOf(segment, form.windowDays());
        List<UUID> audience = sourceRepository.audienceUserIds(segment, windowDays, BROADCAST_MAX_AUDIENCE + 1);
        if (audience.size() > BROADCAST_MAX_AUDIENCE) {
            return new NotificationViews.BroadcastResult(form.dryRun() == null || form.dryRun(),
                    audience.size(), 0, 0, 0, 0, 0, 0,
                    "人群超过单次上限 " + BROADCAST_MAX_AUDIENCE + " 人，请改用更小的分群或分批发送");
        }
        boolean dryRun = form.dryRun() == null || form.dryRun();
        String linkUrl = safeLink(form.linkUrl(), MessagePublisher.EVENT_BROADCAST);
        LocalDateTime now = LocalDateTime.now();

        BroadcastCampaign campaign = null;
        if (!dryRun) {
            campaign = new BroadcastCampaign();
            campaign.setTitle(truncate(form.title().trim(), 200));
            campaign.setContent(truncate(form.content().trim(), 4000));
            campaign.setSegmentCode(segment);
            campaign.setSegmentName(NotificationSourceRepository.SEGMENT_LABELS.get(segment));
            campaign.setCategory(UserMessage.CATEGORY_MARKETING);
            campaign.setStatus(BroadcastCampaign.STATUS_RUNNING);
            campaign.setTargetCount(audience.size());
            campaign.setCreatedBy(operatorName);
            campaign.setCreatedById(operatorId);
            campaign.setStartedAt(now);
            campaign = campaignRepository.save(campaign);
        }

        int sent = 0;
        int skippedPref = 0;
        int skippedDnd = 0;
        int skippedCap = 0;
        int skippedWeekend = 0;
        int failed = 0;
        UUID campaignId = campaign == null ? null : campaign.getId();

        for (UUID userId : audience) {
            NotificationPolicy.Decision decision = decide(userId, UserMessage.CATEGORY_MARKETING, now);
            switch (decision.action()) {
                case SUPPRESS -> {
                    switch (decision.reason()) {
                        case PREF -> skippedPref++;
                        case WEEKEND -> skippedWeekend++;
                        case DAILY_CAP -> skippedCap++;
                        default -> skippedDnd++;
                    }
                }
                case DEFER -> skippedDnd++;
                case DELIVER -> {
                    if (dryRun) {
                        sent++;
                        continue;
                    }
                    try {
                        // 幂等键带活动 id：同一次群发对同一用户只会有一条，重放接口也不会翻倍
                        int rows = messageRepository.insertIfAbsent(UUID.randomUUID(), userId,
                                UserMessage.CATEGORY_MARKETING, truncate(form.title().trim(), 200),
                                truncate(form.content().trim(), 4000), linkUrl,
                                MessagePublisher.EVENT_BROADCAST, String.valueOf(campaignId), null,
                                MessageKeys.of(MessagePublisher.EVENT_BROADCAST, campaignId, userId));
                        sent += rows;
                    } catch (RuntimeException e) {
                        failed++;
                        log.warn("群发落库失败 campaign={} user={}: {}", campaignId, userId, Masking.brief(e));
                    }
                }
                default -> failed++;
            }
        }

        if (campaign != null) {
            campaignRepository.accumulate(campaignId, sent, skippedPref, skippedDnd, skippedCap,
                    skippedWeekend, failed, LocalDateTime.now());
            campaignRepository.finish(campaignId, BroadcastCampaign.STATUS_DONE, audience.size(), null,
                    LocalDateTime.now());
        }
        String note = dryRun
                ? "预览：未写入任何消息行"
                : "已写入 " + sent + " 条；退订 " + skippedPref + "、周末停发 " + skippedWeekend
                        + "、当日上限 " + skippedCap + "、免打扰 " + skippedDnd + "、失败 " + failed;
        return new NotificationViews.BroadcastResult(dryRun, audience.size(), sent, skippedPref,
                skippedDnd, skippedCap, skippedWeekend, failed, note);
    }

    @Override
    public Map<String, Object> previewAudience(String segmentCode, Integer windowDays) {
        String segment = normalizeSegment(segmentCode);
        int days = windowDaysOf(segment, windowDays);
        long count = sourceRepository.countAudience(segment, days);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("segmentCode", segment);
        out.put("segmentLabel", NotificationSourceRepository.SEGMENT_LABELS.get(segment));
        out.put("windowDays", days);
        out.put("count", count);
        out.put("capped", Math.min(count, BROADCAST_MAX_AUDIENCE));
        out.put("singleRunCap", BROADCAST_MAX_AUDIENCE);
        out.put("marketingBlackoutToday", NotificationPolicy.isMarketingBlackoutDay(LocalDateTime.now().toLocalDate()));
        out.put("dailyMarketingCap", NotificationPolicy.DAILY_MARKETING_CAP);
        return out;
    }

    @Override
    public List<Map<String, Object>> audienceSegments() {
        int lookback = 30;
        return NotificationSourceRepository.SEGMENTS.stream()
                .map(code -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("code", code);
                    item.put("label", NotificationSourceRepository.SEGMENT_LABELS.get(code));
                    item.put("count", sourceRepository.countAudience(code, windowDaysOf(code, null)));
                    return item;
                })
                .toList();
    }

    /* ---------- 内部判定 ---------- */

    /** 渲染结果：把「模板还是直发」这一层差异在方法内消化掉，主流程只看 title/content/payload */
    private record Rendered(boolean ok, String reason, String title, String content,
                            String templateCode, String bizId, String payload) {

        static Rendered fail(String reason) {
            return new Rendered(false, reason, null, null, null, null, null);
        }
    }

    private Rendered render(NotificationEvent event) {
        String templateCode = event.templateCode();
        if (templateCode == null || templateCode.isBlank()) {
            if (event.title() == null || event.title().isBlank()) {
                return Rendered.fail("直发消息缺少标题");
            }
            String content = event.content() == null ? "" : event.content().trim();
            return new Rendered(true, null, truncate(event.title().trim(), MessageRenderPolicy.TITLE_MAX),
                    content, null, String.valueOf(event.bizId()), null);
        }
        Optional<MessageTemplate> found = templateRepository.findByCodeAndEnabled(templateCode, true);
        if (found.isEmpty()) {
            return Rendered.fail("模板不存在或已停用：" + templateCode);
        }
        MessageTemplate template = found.get();
        MessageRenderPolicy.Rendered rendered = MessageRenderPolicy.render(template.getTitleTpl(),
                template.getContentTpl(), template.requiredVars(), event.vars());
        if (!rendered.ok()) {
            // U07：缺参整条不落库。发出「订单  已发货」这种半成品，比不发更难解释
            return Rendered.fail(rendered.reason());
        }
        return new Rendered(true, null, rendered.title(), rendered.content(), templateCode,
                String.valueOf(event.bizId()), payloadOf(event));
    }

    /** 偏好 + 档案 + 当日上限的综合判定（U08/U09/U23） */
    private NotificationPolicy.Decision decide(UUID userId, String category, LocalDateTime now) {
        Boolean pref = prefRepository.inboxEnabledFor(userId, category);
        boolean prefEnabled = pref == null || pref;
        var profile = profileRepository.findById(userId).orElse(null);
        boolean dndEnabled = profile == null || Boolean.TRUE.equals(profile.getDndEnabled());
        // 档案里的营销总开关与偏好矩阵取「与」：两处任一处关掉就不发
        if (UserMessage.CATEGORY_MARKETING.equals(category)
                && profile != null && Boolean.TRUE.equals(profile.getMarketingPaused())) {
            prefEnabled = false;
        }
        int marketingToday = UserMessage.CATEGORY_MARKETING.equals(category)
                ? (int) Math.min(Integer.MAX_VALUE, messageRepository.countMarketingSince(userId,
                        now.toLocalDate().atStartOfDay()))
                : 0;
        return NotificationPolicy.decide(UserMessage.CATEGORY_MARKETING.equals(category), prefEnabled, dndEnabled,
                profile == null ? NotificationPolicy.DEFAULT_DND_START : profile.getDndStart(),
                profile == null ? NotificationPolicy.DEFAULT_DND_END : profile.getDndEnd(),
                now, marketingToday);
    }

    /** 跳转地址白名单（U05）：越权的地址不入库，但要留下可计数的一行 */
    private String safeLink(String rawUrl, String bizType) {
        MessageTargetPolicy.Target check = MessageTargetPolicy.check(rawUrl);
        if (check.empty()) {
            return null;
        }
        if (check.rejected()) {
            MessageGuardEvent event = new MessageGuardEvent();
            event.setReason(check.reason());
            event.setRawUrl(truncate(rawUrl, 600));
            event.setSource(bizType);
            event.setOperator("系统");
            guardRepository.save(event);
            log.warn("站内信跳转地址越权已丢弃 reason={} type={}", check.reason(), bizType);
            return null;
        }
        return check.url();
    }

    private static String categoryOf(NotificationEvent event) {
        String bizType = event.bizType();
        if (MARKETING_EVENTS.contains(bizType)) {
            return UserMessage.CATEGORY_MARKETING;
        }
        if (TICKET_EVENTS.contains(bizType)) {
            return UserMessage.CATEGORY_TICKET;
        }
        if (TRADE_EVENTS.contains(bizType)) {
            return UserMessage.CATEGORY_TRADE;
        }
        return UserMessage.CATEGORY_SYSTEM;
    }

    /** 变量快照：值里可能带手机号，落库前先过 Masking（排障够用，不额外多一份隐私） */
    private static String payloadOf(NotificationEvent event) {
        if (event.vars() == null || event.vars().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("{");
        event.vars().forEach((key, value) -> {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append('"').append(sanitize(key)).append("\":\"")
                    .append(sanitize(Masking.scrubText(String.valueOf(value)))).append('"');
        });
        return sb.append('}').toString();
    }

    private static String carePayload(String orderNo, String materials, NotificationSourceRepository.CareArticle hit) {
        return "{\"orderNo\":\"" + sanitize(orderNo) + "\",\"materials\":\"" + sanitize(materials)
                + "\",\"article\":\"" + (hit == null ? "" : sanitize(String.valueOf(hit.articleId()))) + "\"}";
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replace("\\", "").replace("\"", "").replace("\n", " ");
    }

    private static String normalizeSegment(String code) {
        String segment = code == null ? "" : code.trim();
        if (!NotificationSourceRepository.SEGMENTS.contains(segment)) {
            throw new org.liuym.flowerv1springboot.common.BusinessException("不支持的人群分群：" + segment);
        }
        return segment;
    }

    private static int windowDaysOf(String segment, Integer override) {
        if (!"active_buyers".equals(segment) && !"dormant".equals(segment)) {
            return 30;
        }
        return override == null ? NotificationSourceRepository.windowDaysOf(segment)
                : Math.max(1, Math.min(override, 365));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
