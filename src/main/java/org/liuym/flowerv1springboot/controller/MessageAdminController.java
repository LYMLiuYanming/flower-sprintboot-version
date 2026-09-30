package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.AuditSupport;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.NotificationPolicy;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.common.ScheduledJobTracker;
import org.liuym.flowerv1springboot.dto.NotificationDtos;
import org.liuym.flowerv1springboot.model.MessageTemplate;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.repository.MessageTemplateRepository;
import org.liuym.flowerv1springboot.service.AdminAuditService;
import org.liuym.flowerv1springboot.service.MessageCenterService;
import org.liuym.flowerv1springboot.service.MessageDispatchService;
import org.liuym.flowerv1springboot.vo.NotificationViews;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 后台消息模板与发送记录（U05/U06/U07/U21/U23 + U10/U21 的任务可见状态）。
 *
 * <p>模板改动要留痕：文案是顾客看到的原话，出了歧义得能查到「这句话是谁在哪天改的」。
 * 群发默认 dryRun，真正写库的那一次必须显式带 {@code dryRun=false}，并且台账与审计都各留一份。
 */
@RestController
@RequestMapping("/api/admin/messages")
@Tag(name = "后台 · 消息通知")
public class MessageAdminController {

    /** 台账里属于消息中心的两类任务：到点投递与保留期清理，后台页只关心这两个 */
    private static final List<String> JOB_KEYS = List.of("deliverdue", "cleanup", "retention", "scheduled");

    private final MessageCenterService messageCenterService;
    private final MessageDispatchService messageDispatchService;
    private final MessageTemplateRepository templateRepository;
    private final ScheduledJobTracker jobTracker;
    private final AdminAuditService auditService;

    public MessageAdminController(MessageCenterService messageCenterService,
                                  MessageDispatchService messageDispatchService,
                                  MessageTemplateRepository templateRepository,
                                  ScheduledJobTracker jobTracker,
                                  AdminAuditService auditService) {
        this.messageCenterService = messageCenterService;
        this.messageDispatchService = messageDispatchService;
        this.templateRepository = templateRepository;
        this.jobTracker = jobTracker;
        this.auditService = auditService;
    }

    /* ---------- 概览与明细 ---------- */

    @GetMapping("/overview")
    public Result<Map<String, Object>> overview(HttpSession session) {
        CurrentUser.requireAdmin(session);
        Map<String, Object> data = new LinkedHashMap<>(messageCenterService.overview());
        data.put("quietHoursDefault", NotificationPolicy.DEFAULT_DND_START + "-"
                + NotificationPolicy.DEFAULT_DND_END);
        data.put("dailyMarketingCap", NotificationPolicy.DAILY_MARKETING_CAP);
        return Result.ok(data);
    }

    /** U04/U23 触达明细：收件人手机号出网前按 Masked 口径打码 */
    @GetMapping("/records")
    public Result<List<NotificationViews.MessageView>> records(@RequestParam(required = false) String category,
                                                               @RequestParam(required = false) String bizType,
                                                               @RequestParam(required = false) UUID userId,
                                                               @RequestParam(required = false) String keyword,
                                                               @RequestParam(defaultValue = "1") int page,
                                                               @RequestParam(defaultValue = "20") int limit,
                                                               HttpSession session) {
        CurrentUser.requireAdmin(session);
        Page<NotificationViews.MessageView> found = messageCenterService.searchForAdmin(
                new NotificationDtos.MessageQuery(category, bizType, userId, keyword, null, null, page, limit));
        return Result.page(found.getContent(), found.getTotalElements());
    }

    /** U05 越权跳转统计：有人在拿站内信发外链时，这一卡会先红 */
    @GetMapping("/guard")
    public Result<NotificationViews.GuardStat> guard(HttpSession session) {
        CurrentUser.requireAdmin(session);
        return Result.ok(messageCenterService.guardStat());
    }

    /** U06 事件接入清单：八类事件的近况，新事件接进来在这里能立刻看到有没有量 */
    @GetMapping("/events")
    public Result<List<NotificationViews.EventIngestRow>> events(HttpSession session) {
        CurrentUser.requireAdmin(session);
        return Result.ok(messageCenterService.eventIngestBoard());
    }

    /* ---------- U07 模板 ---------- */

    @GetMapping("/templates")
    public Result<List<NotificationViews.TemplateView>> templates(@RequestParam(required = false) String category,
                                                                  @RequestParam(required = false) String keyword,
                                                                  @RequestParam(defaultValue = "false") boolean enabledOnly,
                                                                  @RequestParam(defaultValue = "1") int page,
                                                                  @RequestParam(defaultValue = "20") int limit,
                                                                  HttpSession session) {
        CurrentUser.requireAdmin(session);
        Page<NotificationViews.TemplateView> found =
                messageCenterService.templates(category, keyword, enabledOnly, page, limit);
        return Result.page(found.getContent(), found.getTotalElements());
    }

    @PostMapping("/templates")
    public Result<NotificationViews.TemplateView> createTemplate(@Valid @RequestBody NotificationDtos.TemplateForm form,
                                                                 HttpSession session, HttpServletRequest request) {
        User operator = CurrentUser.requireAdmin(session);
        NotificationViews.TemplateView view =
                messageCenterService.saveTemplate(null, form, operator.getUsername());
        audit(request, operator, "新增消息模板", view.code(),
                "类别 " + view.category() + "｜槽位 " + String.join(",", view.slots()));
        return Result.ok("模板已保存", view);
    }

    @PutMapping("/templates/{id}")
    public Result<NotificationViews.TemplateView> updateTemplate(@PathVariable UUID id,
                                                                 @Valid @RequestBody NotificationDtos.TemplateForm form,
                                                                 HttpSession session, HttpServletRequest request) {
        User operator = CurrentUser.requireAdmin(session);
        String before = templateRepository.findById(id).map(MessageTemplate::getContentTpl).orElse("");
        NotificationViews.TemplateView view =
                messageCenterService.saveTemplate(id, form, operator.getUsername());
        audit(request, operator, "编辑消息模板", view.code(),
                "正文改动 " + (before.equals(form.contentTpl()) ? "无" : "有")
                        + "｜槽位 " + String.join(",", view.slots()));
        return Result.ok("模板已更新", view);
    }

    /** 启停模板：停用后同类事件会整条不落库并回「模板不存在或已停用」，不是静默降级成空白文案 */
    @PostMapping("/templates/{id}/toggle")
    public Result<Map<String, Object>> toggleTemplate(@PathVariable UUID id,
                                                      @RequestParam boolean enabled,
                                                      HttpSession session, HttpServletRequest request) {
        User operator = CurrentUser.requireAdmin(session);
        int rows = messageCenterService.toggleTemplate(id, enabled);
        String code = templateRepository.findById(id).map(MessageTemplate::getCode).orElse("-");
        audit(request, operator, enabled ? "启用消息模板" : "停用消息模板", code,
                rows == 0 ? "状态未变化" : "已切换为" + (enabled ? "启用" : "停用"));
        return Result.ok(rows == 0 ? "模板已经是这个状态" : "操作成功", Map.of("changed", rows));
    }

    /* ---------- U21 延期队列 / U23 群发台账 ---------- */

    @GetMapping("/scheduled")
    public Result<List<NotificationViews.ScheduledView>> scheduled(@RequestParam(required = false) String status,
                                                                   @RequestParam(required = false) String bizType,
                                                                   @RequestParam(defaultValue = "60") int limit,
                                                                   HttpSession session) {
        CurrentUser.requireAdmin(session);
        return Result.ok(messageCenterService.scheduled(status, bizType, limit));
    }

    /** U21 手工补投：只把到点的行投出去，抢占仍是条件 UPDATE，点两次不会双发 */
    @PostMapping("/scheduled/dispatch")
    public Result<Map<String, Object>> dispatchNow(@RequestParam(defaultValue = "100") int limit,
                                                  HttpSession session, HttpServletRequest request) {
        User operator = CurrentUser.requireAdmin(session);
        int delivered = messageDispatchService.deliverDueScheduled(limit);
        audit(request, operator, "手工投递延时消息", null, "本轮新增落库 " + delivered + " 条");
        return Result.ok("已投递 " + delivered + " 条", Map.of("delivered", delivered));
    }

    /** U10 手工触发一次保留期清理：归档计数落表后物理删除，页面据此回答「老消息去哪了」 */
    @PostMapping("/retention/run")
    public Result<NotificationViews.RetentionResult> runRetention(HttpSession session, HttpServletRequest request) {
        User operator = CurrentUser.requireAdmin(session);
        NotificationViews.RetentionResult result = messageDispatchService.cleanupExpiredMessages();
        audit(request, operator, "执行消息保留期清理", null,
                "保留 " + result.retentionDays() + " 天｜删除 " + result.deletedCount()
                        + " 条｜归档分桶 " + result.archivedBuckets() + " 个");
        return Result.ok(result.note(), result);
    }

    @GetMapping("/campaigns")
    public Result<List<NotificationViews.CampaignView>> campaigns(@RequestParam(defaultValue = "1") int page,
                                                                  @RequestParam(defaultValue = "20") int limit,
                                                                  HttpSession session) {
        CurrentUser.requireAdmin(session);
        Page<NotificationViews.CampaignView> found = messageCenterService.campaigns(page, limit);
        return Result.page(found.getContent(), found.getTotalElements());
    }

    /* ---------- U23 群发 ---------- */

    @GetMapping("/segments")
    public Result<List<Map<String, Object>>> segments(HttpSession session) {
        CurrentUser.requireAdmin(session);
        return Result.ok(messageDispatchService.audienceSegments());
    }

    @PostMapping("/broadcast/preview")
    public Result<Map<String, Object>> previewBroadcast(@RequestParam String segmentCode,
                                                        @RequestParam(required = false) Integer windowDays,
                                                        HttpSession session) {
        CurrentUser.requireAdmin(session);
        return Result.ok(messageDispatchService.previewAudience(segmentCode, windowDays));
    }

    /**
     * U23 群发执行。
     *
     * <p>默认 dryRun：预览与实发是同一个接口、同一套判定，只差一个参数，
     * 这样「预览说会发多少人」和「实际发了多少人」不可能对不上。
     */
    @PostMapping("/broadcast")
    public Result<NotificationViews.BroadcastResult> broadcast(@Valid @RequestBody NotificationDtos.BroadcastForm form,
                                                               HttpSession session, HttpServletRequest request) {
        User operator = CurrentUser.requireAdmin(session);
        NotificationViews.BroadcastResult result =
                messageDispatchService.broadcast(form, operator.getId(), operator.getUsername());
        audit(request, operator, result.dryRun() ? "群发预览" : "执行群发", form.title(),
                "人群 " + result.targetCount() + "｜实发 " + result.sent()
                        + "｜退订拦 " + result.skippedPref() + "｜周末停 " + result.skippedWeekend()
                        + "｜上限拦 " + result.skippedCap() + "｜免打扰 " + result.skippedDnd()
                        + "｜失败 " + result.failed());
        return Result.ok(result.note(), result);
    }

    /* ---------- U10/U21 任务可见状态 ---------- */

    /**
     * 复用第一轮 {@link ScheduledJobTracker} 的台账，只挑出消息中心自己的两类任务。
     *
     * <p>这里不新建任何监控：台账由调度线程池的装饰器自动采集，本接口只做一次名字过滤，
     * 因此新增一个 @Scheduled 方法会自动出现在这里，不需要再改代码。
     */
    @GetMapping("/jobs")
    public Result<Map<String, Object>> jobs(HttpSession session) {
        CurrentUser.requireAdmin(session);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ScheduledJobTracker.JobState state : jobTracker.snapshot()) {
            String lower = state.job().toLowerCase(Locale.ROOT);
            if (JOB_KEYS.stream().noneMatch(lower::contains)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("job", state.job());
            row.put("lastRunAt", state.lastRunAt());
            row.put("lastDurationMs", state.lastDurationMs());
            row.put("lastOutcome", state.lastOutcome());
            row.put("lastError", state.lastError());
            row.put("lastReported", state.lastReported());
            row.put("runs", state.runs());
            row.put("failures", state.failures());
            row.put("healthy", state.healthy());
            row.put("healthNote", state.healthNote());
            row.put("minutesSinceSuccess", state.minutesSinceSuccess());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobs", rows);
        out.put("registered", !rows.isEmpty());
        out.put("summary", jobTracker.summary());
        out.put("hint", rows.isEmpty()
                ? "调度台账里还没有消息中心的任务：说明 ScheduledMessageTasks 未被扫描到，或还没有跑过第一轮"
                : LocalDateTime.now());
        return Result.ok(out);
    }

    private void audit(HttpServletRequest request, User operator, String action, Object target, String detail) {
        auditService.record(AuditSupport.entry(operator, "消息通知", action, request.getMethod(),
                request.getRequestURI() + (target == null ? "" : " → " + target), detail, 200, action + "成功"));
    }
}
