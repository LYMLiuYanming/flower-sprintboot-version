package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.MessageKeys;
import org.liuym.flowerv1springboot.common.MessageRenderPolicy;
import org.liuym.flowerv1springboot.common.NotificationPolicy;
import org.liuym.flowerv1springboot.common.Pages;
import org.liuym.flowerv1springboot.dto.NotificationDtos;
import org.liuym.flowerv1springboot.model.MessageGuardEvent;
import org.liuym.flowerv1springboot.model.MessageTemplate;
import org.liuym.flowerv1springboot.model.NotificationPref;
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
import org.liuym.flowerv1springboot.service.MessageCenterService;
import org.liuym.flowerv1springboot.vo.NotificationViews;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 消息中心读侧实现（U03/U04/U05/U07/U08/U21/U23）。
 *
 * <p>写动作只落在两处：已读位与偏好行，二者都是条件 UPDATE / upsert，
 * 重复点击不会把 read_at 覆盖成第二个时间，也不会把已经关着的开关「再关一次」计成变更。
 */
@Service
@Transactional
public class MessageCenterServiceImpl implements MessageCenterService {

    /** 铃铛下拉的条数（U03） */
    private static final int BADGE_RECENT = 5;

    /** 偏好矩阵里唯一真正可用的渠道：其余渠道提交 true 一律拒绝，避免用户以为短信已开通 */
    private static final Set<String> CATEGORIES = Set.of(
            UserMessage.CATEGORY_TRADE, UserMessage.CATEGORY_MARKETING,
            UserMessage.CATEGORY_SYSTEM, UserMessage.CATEGORY_TICKET);

    private final UserMessageRepository messageRepository;
    private final ScheduledMessageRepository scheduledRepository;
    private final MessageTemplateRepository templateRepository;
    private final NotificationPrefRepository prefRepository;
    private final NotificationProfileRepository profileRepository;
    private final MessageGuardEventRepository guardRepository;
    private final BroadcastCampaignRepository campaignRepository;
    private final MessageArchiveStatRepository archiveRepository;
    private final NotificationSourceRepository sourceRepository;

    private final int retentionDays;

    public MessageCenterServiceImpl(UserMessageRepository messageRepository,
                                    ScheduledMessageRepository scheduledRepository,
                                    MessageTemplateRepository templateRepository,
                                    NotificationPrefRepository prefRepository,
                                    NotificationProfileRepository profileRepository,
                                    MessageGuardEventRepository guardRepository,
                                    BroadcastCampaignRepository campaignRepository,
                                    MessageArchiveStatRepository archiveRepository,
                                    NotificationSourceRepository sourceRepository,
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
        this.retentionDays = Math.max(7, retentionDays);
    }

    /* ---------- U03 角标 ---------- */

    @Override
    @Transactional(readOnly = true)
    public NotificationViews.UnreadBadge unreadBadge(UUID userId) {
        if (userId == null) {
            return new NotificationViews.UnreadBadge(0, List.of(), List.of());
        }
        long unread = messageRepository.countByUserIdAndIsReadFalse(userId);
        List<UserMessage> recent = messageRepository.findRecent(userId, PageRequest.of(0, BADGE_RECENT));
        List<NotificationViews.MessageView> views = recent.stream().map(NotificationViews.MessageView::of).toList();
        return new NotificationViews.UnreadBadge(unread, views, categoryCounts(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return userId == null ? 0 : messageRepository.countByUserIdAndIsReadFalse(userId);
    }

    /** 分类计数：一条分组查询同时拿回总量与未读量，铃铛 30 秒轮询才不会打八次库 */
    private List<NotificationViews.CategoryCount> categoryCounts(UUID userId) {
        Map<String, long[]> buckets = new LinkedHashMap<>();
        for (Object[] row : messageRepository.categorySummary(userId)) {
            String category = (String) row[0];
            if (category != null) {
                buckets.put(category, new long[]{((Number) row[1]).longValue(),
                        row[2] == null ? 0 : ((Number) row[2]).longValue()});
            }
        }
        List<NotificationViews.CategoryCount> out = new ArrayList<>(4);
        for (String category : List.of(UserMessage.CATEGORY_TRADE, UserMessage.CATEGORY_MARKETING,
                UserMessage.CATEGORY_SYSTEM, UserMessage.CATEGORY_TICKET)) {
            long[] hit = buckets.getOrDefault(category, new long[]{0, 0});
            out.add(new NotificationViews.CategoryCount(category,
                    NotificationViews.categoryLabel(category), hit[0], hit[1]));
        }
        return out;
    }

    /* ---------- U04 列表与已读 ---------- */

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationViews.MessageView> listForUser(UUID userId, String category, boolean unreadOnly,
                                                           int page, int limit) {
        if (userId == null) {
            return Page.empty();
        }
        return messageRepository
                .searchForUser(userId, normalizeCategory(category), unreadOnly,
                        Pages.of(page, limit, Sort.Direction.DESC, "createdAt"))
                .map(NotificationViews.MessageView::of);
    }

    @Override
    public int markRead(UUID messageId, UUID userId) {
        if (messageId == null || userId == null) {
            return 0;
        }
        // 条件 UPDATE 自带「未读」判断：0 行就是本来就读过，不是错误，页面按幂等成功处理
        return messageRepository.markRead(messageId, userId, LocalDateTime.now());
    }

    @Override
    public int markAllRead(UUID userId, String category) {
        if (userId == null) {
            return 0;
        }
        return messageRepository.markAllRead(userId, normalizeCategory(category), LocalDateTime.now());
    }

    @Override
    public boolean deleteOwn(UUID messageId, UUID userId) {
        Optional<UserMessage> found = messageId == null || userId == null
                ? Optional.empty() : messageRepository.findByIdAndUserId(messageId, userId);
        if (found.isEmpty()) {
            // 别人的消息 id 拼进 URL：当作不存在，不回「无权限」，免得变成一条探测探针
            throw BusinessException.notFound("消息不存在或已删除");
        }
        messageRepository.delete(found.get());
        return true;
    }

    /* ---------- U08 订阅偏好 ---------- */

    @Override
    @Transactional(readOnly = true)
    public NotificationViews.PreferenceView preferences(UUID userId) {
        List<NotificationPref> prefs = userId == null ? List.of() : prefRepository.findByUserId(userId);
        var profile = userId == null ? null : profileRepository.findById(userId).orElse(null);
        return NotificationViews.ofPreference(prefs,
                profile == null || Boolean.TRUE.equals(profile.getDndEnabled()),
                profile == null ? NotificationPolicy.DEFAULT_DND_START : profile.getDndStart(),
                profile == null ? NotificationPolicy.DEFAULT_DND_END : profile.getDndEnd(),
                profile != null && Boolean.TRUE.equals(profile.getMarketingPaused()));
    }

    @Override
    public NotificationViews.PreferenceView savePreferences(UUID userId, NotificationDtos.PrefMatrixForm form) {
        if (userId == null) {
            throw new BusinessException("请先登录");
        }
        List<NotificationDtos.PrefForm> cells = form.prefs() == null ? List.of() : form.prefs();
        for (NotificationDtos.PrefForm cell : cells) {
            if (cell == null || cell.category() == null || cell.channel() == null) {
                continue;
            }
            if (!CATEGORIES.contains(cell.category())) {
                throw new BusinessException("消息类别不合法：" + cell.category());
            }
            if (!NotificationPref.CHANNEL_INBOX.equals(cell.channel()) && Boolean.TRUE.equals(cell.enabled())) {
                // 静默忽略会让用户以为短信提醒已经打开；必须点名拒绝
                throw new BusinessException(channelName(cell.channel()) + "通道未开通，无法开启，请改用站内信");
            }
        }
        for (NotificationDtos.PrefForm cell : cells) {
            if (cell == null || !NotificationPref.CHANNEL_INBOX.equals(cell.channel())) {
                continue;
            }
            if (!CATEGORIES.contains(cell.category())) {
                continue;
            }
            prefRepository.upsert(UUID.randomUUID(), userId, cell.category(),
                    NotificationPref.CHANNEL_INBOX, Boolean.TRUE.equals(cell.enabled()));
        }
        LocalTime start = form.dndStart() == null ? NotificationPolicy.DEFAULT_DND_START : form.dndStart();
        LocalTime end = form.dndEnd() == null ? NotificationPolicy.DEFAULT_DND_END : form.dndEnd();
        boolean dndEnabled = form.dndEnabled() == null || form.dndEnabled();
        boolean marketingPaused = Boolean.TRUE.equals(form.marketingPaused());
        profileRepository.ensureExists(userId);
        profileRepository.updateSettings(userId, dndEnabled, start, end, marketingPaused, LocalDateTime.now());
        return preferences(userId);
    }

    @Override
    public NotificationViews.PreferenceView unsubscribeMarketing(UUID userId, boolean unsubscribe) {
        if (userId == null) {
            throw new BusinessException("请先登录");
        }
        prefRepository.upsert(UUID.randomUUID(), userId, UserMessage.CATEGORY_MARKETING,
                NotificationPref.CHANNEL_INBOX, !unsubscribe);
        profileRepository.ensureExists(userId);
        var profile = profileRepository.findById(userId).orElse(null);
        boolean dndEnabled = profile == null || Boolean.TRUE.equals(profile.getDndEnabled());
        LocalTime start = profile == null ? NotificationPolicy.DEFAULT_DND_START : profile.getDndStart();
        LocalTime end = profile == null ? NotificationPolicy.DEFAULT_DND_END : profile.getDndEnd();
        profileRepository.updateSettings(userId, dndEnabled, start, end, unsubscribe, LocalDateTime.now());
        return preferences(userId);
    }

    /* ---------- U07 模板 ---------- */

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationViews.TemplateView> templates(String category, String keyword, boolean enabledOnly,
                                                           int page, int limit) {
        return templateRepository
                .searchAdmin(normalizeCategory(category), keyword == null ? "" : keyword.trim(), enabledOnly,
                        Pages.of(page, limit, Sort.Direction.ASC, "code"))
                .map(NotificationViews.TemplateView::of);
    }

    @Override
    public NotificationViews.TemplateView saveTemplate(UUID id, NotificationDtos.TemplateForm form, String operator) {
        if (form == null) {
            throw new BusinessException("请填写模板内容");
        }
        MessageTemplate template = id == null ? new MessageTemplate()
                : templateRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("模板不存在"));
        String code = form.code().trim();
        boolean isNew = template.getId() == null;
        if (isNew && templateRepository.existsByCode(code)) {
            throw new BusinessException("模板编码已存在：" + code);
        }
        // 槽位与声明的必填变量必须一一对应，否则上线后第一条真实事件就会因为缺参被整条丢弃
        Set<String> slots = MessageRenderPolicy.slotsOf(form.titleTpl(), form.contentTpl());
        List<String> declared = splitKeys(form.varKeys());
        List<String> undeclared = slots.stream().filter(slot -> !declared.contains(slot)).toList();
        if (!undeclared.isEmpty()) {
            throw new BusinessException("模板里用了未声明的变量：" + String.join("、", undeclared)
                    + "；请在「必填变量」里补上，或去掉这些占位符");
        }
        List<String> unused = declared.stream().filter(slot -> !slots.contains(slot)).toList();
        if (!unused.isEmpty()) {
            throw new BusinessException("声明了但模板里没用到的变量：" + String.join("、", unused));
        }
        template.setCode(code);
        template.setCategory(form.category());
        template.setTitleTpl(form.titleTpl().trim());
        template.setContentTpl(form.contentTpl());
        template.setVarKeys(String.join(",", declared));
        template.setEnabled(form.enabled() == null || form.enabled());
        template.setRemark(form.remark());
        MessageTemplate saved = templateRepository.save(template);
        return NotificationViews.TemplateView.of(saved);
    }

    @Override
    public int toggleTemplate(UUID id, boolean enabled) {
        // IS DISTINCT FROM 条件：状态没变就是 0 行，后台据此回「已经是这个状态」而不是假报成功
        return templateRepository.updateEnabledCas(id, enabled);
    }

    /* ---------- U21 待发清单 / U06 事件台账 / U05 越权 ---------- */

    @Override
    @Transactional(readOnly = true)
    public List<NotificationViews.ScheduledView> scheduled(String status, String bizType, int limit) {
        LocalDateTime now = LocalDateTime.now();
        // 只看未来 90 天与过去 30 天：更早的排期行早已被清理任务归档，列出来对排障没有意义
        List<ScheduledMessage> rows = scheduledRepository.search(blankToEmpty(status), blankToEmpty(bizType),
                now.minusDays(30), now.plusDays(90), PageRequest.of(0, Math.max(1, Math.min(limit, 200))));
        return rows.stream().map(NotificationViews.ScheduledView::of).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationViews.EventIngestRow> eventIngestBoard() {
        LocalDateTime from = LocalDateTime.now().minusHours(24);
        Map<String, Long> delivered = new LinkedHashMap<>();
        for (Object[] row : messageRepository.eventDistribution(from)) {
            String bizType = (String) row[0];
            if (bizType != null) {
                delivered.merge(bizType, ((Number) row[1]).longValue(), Long::sum);
            }
        }
        Map<String, Long> undelivered = new LinkedHashMap<>();
        for (Object[] row : scheduledRepository.undeliveredByBizType()) {
            String bizType = (String) row[0];
            if (bizType != null) {
                undelivered.merge(bizType, ((Number) row[1]).longValue(), Long::sum);
            }
        }
        List<NotificationViews.EventIngestRow> out = new ArrayList<>(MessageKeys.EVENT_LABELS.size());
        for (Map.Entry<String, String> entry : MessageKeys.EVENT_LABELS.entrySet()) {
            long count = delivered.getOrDefault(entry.getKey(), 0L);
            long waiting = undelivered.getOrDefault(entry.getKey(), 0L);
            String note = waiting > 0 ? "另有 " + waiting + " 条在延期队列或等待重试"
                    : count > 0 ? "近 24 小时已全部落库" : "近 24 小时无触达";
            out.add(new NotificationViews.EventIngestRow(entry.getKey(), entry.getValue(), count, waiting, note));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationViews.GuardStat guardStat() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Long> byReason = new LinkedHashMap<>();
        for (Object[] row : guardRepository.countByReasonSince(now.minusDays(7))) {
            byReason.merge((String) row[0], ((Number) row[1]).longValue(), Long::sum);
        }
        long total7d = byReason.values().stream().mapToLong(Long::longValue).sum();
        List<Map<String, Object>> rows = byReason.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .<Map<String, Object>>map(e -> Map.of("reason", e.getKey(), "count", e.getValue()))
                .toList();
        List<String> recent = guardRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 8)).getContent()
                .stream().map(MessageGuardEvent::getReason).distinct().toList();
        return new NotificationViews.GuardStat(guardRepository.countByCreatedAtAfter(now.minusHours(24)),
                total7d, rows, recent);
    }

    /* ---------- U23 台账与概览 ---------- */

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationViews.MessageView> searchForAdmin(NotificationDtos.MessageQuery query) {
        int page = query == null ? 1 : query.page();
        int limit = query == null ? 20 : query.limit();
        Page<UserMessage> found = messageRepository.searchForAdmin(
                query == null ? "" : normalizeCategory(query.category()),
                query == null ? "" : blankToEmpty(query.bizType()),
                query == null ? null : query.userId(),
                query == null ? "" : blankToEmpty(query.keyword()),
                Pages.of(page, limit, Sort.Direction.DESC, "createdAt"));
        // 收件人手机号只在后台明细出现，且由 @Masked 出网前打码
        Map<UUID, String> phones = found.getContent().isEmpty() ? Map.of()
                : sourceRepository.phonesOf(found.getContent().stream().map(UserMessage::getUserId).distinct().toList());
        return found.map(m -> NotificationViews.MessageView.ofAdmin(m, phones.get(m.getUserId())));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationViews.CampaignView> campaigns(int page, int limit) {
        return campaignRepository.findAllByOrderByCreatedAtDesc(Pages.of(page, limit))
                .map(NotificationViews.CampaignView::of);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("retained", messageRepository.countRetained(now.minusDays(retentionDays)));
        out.put("templates", templateRepository.countByEnabledTrue());
        out.put("pendingSchedule", scheduledRepository.statusSummary().stream()
                .filter(r -> ScheduledMessage.STATUS_PENDING.equals(r[0]) || ScheduledMessage.STATUS_SENDING.equals(r[0]))
                .mapToLong(r -> ((Number) r[1]).longValue()).sum());
        out.put("runningCampaigns", campaignRepository.countRunning());
        out.put("archivedBuckets", archiveRepository.monthlySummary().size());
        out.put("archivedTotal", archiveRepository.monthlySummary().stream()
                .mapToLong(r -> ((Number) r[1]).longValue()).sum());
        out.put("retentionDays", retentionDays);
        out.put("guard24h", guardRepository.countByCreatedAtAfter(now.minusHours(24)));
        return out;
    }

    /* ---------- 小工具 ---------- */

    private static String normalizeCategory(String category) {
        String value = category == null ? "" : category.trim();
        return CATEGORIES.contains(value) ? value : "";
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> splitKeys(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split("[,，]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private static String channelName(String channel) {
        return switch (channel) {
            case NotificationPref.CHANNEL_SMS -> "短信";
            case NotificationPref.CHANNEL_EMAIL -> "邮件";
            default -> "该";
        };
    }
}
