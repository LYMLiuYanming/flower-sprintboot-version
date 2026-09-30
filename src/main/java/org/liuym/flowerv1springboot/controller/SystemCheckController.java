package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.common.ScheduledJobTracker;
import org.liuym.flowerv1springboot.common.SystemHealthProbe;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.Map;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 系统运行台（L10/L15）：启动自检结果 + 定时任务台账的后台页面与只读接口。
 *
 * <p>页面路由自己声明（不挤进 {@code AdminController} 那张路由表），是为了不与并行批次改同一个文件；
 * 挂在 /admin/** 下即可复用鉴权拦截器的管理员校验。
 */
@Controller
@Tag(name = "后台 · 系统运行台")
public class SystemCheckController {

    private final SystemHealthProbe healthProbe;
    private final ScheduledJobTracker jobTracker;

    public SystemCheckController(SystemHealthProbe healthProbe, ScheduledJobTracker jobTracker) {
        this.healthProbe = healthProbe;
        this.jobTracker = jobTracker;
    }

    /** 后台页：任务台账 + 依赖/SQL/缓存/限流四块面板，数据由页面另发只读诊断请求取 */
    @GetMapping("/admin/system-jobs")
    public String jobsPage(HttpSession session, Model model) {
        CurrentUser.requireAdmin(session);
        model.addAttribute("operator", CurrentUser.require(session).getUsername());
        model.addAttribute("trackerStartedAt", jobTracker.summary().get("trackerStartedAt"));
        return "admin/system-jobs";
    }

    /** 立即复检（L15）：值只回状态与说明，凭据类项目连长度都不回 */
    @PostMapping("/api/admin/system/check")
    @ResponseBody
    public Result<Map<String, Object>> recheck(HttpSession session) {
        CurrentUser.requireAdmin(session);
        SystemHealthProbe.Report report = healthProbe.check();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("at", report.at());
        data.put("healthy", report.healthy());
        data.put("failed", report.failed());
        data.put("warned", report.warned());
        data.put("checks", healthProbe.checksAsMaps());
        return Result.ok(data);
    }

    /** 首屏用：返回上次自检结论，不重新打库，免得每次刷新后台页都发一遍探测 */
    @GetMapping("/api/admin/system/check")
    @ResponseBody
    public Result<Map<String, Object>> lastCheck(HttpSession session) {
        CurrentUser.requireAdmin(session);
        SystemHealthProbe.Report report = healthProbe.last();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("at", report.at());
        data.put("healthy", report.healthy());
        data.put("checks", healthProbe.checksAsMaps());
        return Result.ok(data);
    }
}
