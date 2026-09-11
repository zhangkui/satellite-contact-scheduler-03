package com.gs.schedule.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 预检报告视图对象：实体字段 + 解析后的筛选条件、指标摘要与逐窗口明细。
 */
@Data
public class PrecheckReportVO {
    private Long id;
    private String requestHash;
    private LocalDateTime rangeStart;
    private LocalDateTime rangeEnd;
    private List<Long> stationIds;
    private List<Long> satelliteIds;
    private String dataVersion;
    private String status;
    private Integer totalWindows;
    private Integer schedulableCount;
    private Integer rejectedCount;
    /** 指标摘要：冲突类型计数、受影响资源、跨午夜统计、告警。 */
    private Map<String, Object> summary;
    /** 逐窗口分析明细（含冲突对象与时间段），列表接口不返回。 */
    private List<Map<String, Object>> windows;
    private String errorMessage;
    private String operator;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** true 表示本次请求命中同参数既有报告，未重新分析（幂等返回）。 */
    private Boolean cached;
}
