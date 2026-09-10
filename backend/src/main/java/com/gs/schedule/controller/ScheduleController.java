package com.gs.schedule.controller;

import com.gs.schedule.common.R;
import com.gs.schedule.dto.GenerateRequest;
import com.gs.schedule.dto.GenerateResult;
import com.gs.schedule.dto.TaskAdjustRequest;
import com.gs.schedule.dto.TaskCreateRequest;
import com.gs.schedule.dto.TaskVO;
import com.gs.schedule.entity.ScheduleAudit;
import com.gs.schedule.entity.ScheduleVersion;
import com.gs.schedule.service.ScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 排程操作：生成草稿、手工提交、调整、取消、发布、修订、版本对比。
 * 冲突类错误统一以 HTTP 409 返回，响应体 data 中携带冲突对象与时间段。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    // ---------------- 自动排程 ----------------

    @PostMapping("/schedule/generate")
    public R<GenerateResult> generate(@Valid @RequestBody GenerateRequest request) {
        return R.ok(scheduleService.generate(request));
    }

    // ---------------- 版本 ----------------

    @GetMapping("/versions")
    public R<List<ScheduleVersion>> versions() {
        return R.ok(scheduleService.listVersions());
    }

    @GetMapping("/versions/{id}")
    public R<Map<String, Object>> versionDetail(@PathVariable Long id,
                                                @RequestParam(defaultValue = "false") boolean includeCancelled) {
        ScheduleVersion v = scheduleService.getVersion(id);
        v.setSnapshot(null); // 详情不回传大快照
        List<TaskVO> tasks = scheduleService.versionTasks(id, includeCancelled);
        List<ScheduleAudit> audits = scheduleService.audits(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", v);
        data.put("tasks", tasks);
        data.put("audits", audits);
        return R.ok(data);
    }

    @PostMapping("/versions/{id}/publish")
    public R<Map<String, Object>> publish(@PathVariable Long id,
                                          @RequestBody(required = false) Map<String, String> body) {
        String operator = body == null ? null : body.get("operator");
        return R.ok(scheduleService.publish(id, operator));
    }

    /** 基于已发布版本创建修订草稿（发布后只能通过新版本修订）。 */
    @PostMapping("/versions/{id}/revise")
    public R<Map<String, Object>> revise(@PathVariable Long id) {
        return R.ok(scheduleService.createRevision(id));
    }

    @GetMapping("/versions/compare")
    public R<Map<String, Object>> compare(@RequestParam Long left, @RequestParam Long right) {
        return R.ok(scheduleService.compare(left, right));
    }

    // ---------------- 任务 ----------------

    @PostMapping("/tasks")
    public R<TaskVO> createTask(@Valid @RequestBody TaskCreateRequest request) {
        return R.ok(scheduleService.addTask(request));
    }

    @PostMapping("/tasks/{id}/adjust")
    public R<TaskVO> adjustTask(@PathVariable Long id, @Valid @RequestBody TaskAdjustRequest request) {
        return R.ok(scheduleService.adjustTask(id, request));
    }

    @PostMapping("/tasks/{id}/cancel")
    public R<TaskVO> cancelTask(@PathVariable Long id,
                                @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        return R.ok(scheduleService.cancelTask(id, reason));
    }

    @DeleteMapping("/tasks/{id}")
    public R<Void> removeTask(@PathVariable Long id) {
        scheduleService.removeTask(id);
        return R.ok();
    }
}
