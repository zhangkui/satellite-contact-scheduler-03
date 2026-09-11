package com.gs.schedule.controller;

import com.gs.schedule.common.R;
import com.gs.schedule.dto.PrecheckRequest;
import com.gs.schedule.dto.PrecheckReportVO;
import com.gs.schedule.service.PrecheckService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 排程预检：干跑分析（幂等持久化）、报告查询、依据预检生成草稿。
 * 预检本身只读，不触碰任何排程草稿。
 */
@RestController
@RequestMapping("/api/precheck")
@RequiredArgsConstructor
public class PrecheckController {

    private final PrecheckService precheckService;

    /** 执行预检；同一参数重复请求返回既有报告（cached=true），不产生重复分析记录。 */
    @PostMapping
    public R<PrecheckReportVO> run(@RequestBody PrecheckRequest request) {
        return R.ok(precheckService.run(request));
    }

    /** 历史报告列表（不含逐窗口明细）。 */
    @GetMapping
    public R<List<PrecheckReportVO>> list() {
        return R.ok(precheckService.listReports());
    }

    /** 报告详情（含指标摘要与逐窗口冲突明细），可重复查看。 */
    @GetMapping("/{id}")
    public R<PrecheckReportVO> detail(@PathVariable Long id) {
        return R.ok(precheckService.getReport(id));
    }

    /** 依据预检报告参数生成排程草稿（始终新建草稿，不改变既有草稿）。 */
    @PostMapping("/{id}/generate")
    public R<Map<String, Object>> generate(@PathVariable Long id,
                                           @RequestBody(required = false) Map<String, String> body) {
        String operator = body == null ? null : body.get("operator");
        return R.ok(precheckService.generateFromReport(id, operator));
    }
}
