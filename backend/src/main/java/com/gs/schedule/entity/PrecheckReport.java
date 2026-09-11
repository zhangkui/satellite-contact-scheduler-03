package com.gs.schedule.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 预检报告。按 request_hash（排程范围 + 站点/卫星筛选归一化后的 SHA-256）幂等：
 * 同一参数重复请求直接返回既有记录，不产生重复分析记录。
 */
@Data
@TableName("precheck_report")
public class PrecheckReport {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 请求参数 SHA-256（范围 + 筛选归一化）。 */
    private String requestHash;
    private LocalDateTime rangeStart;
    private LocalDateTime rangeEnd;
    /** 站点筛选 JSON 数组，空为全部。 */
    private String stationIds;
    /** 卫星筛选 JSON 数组，空为全部。 */
    private String satelliteIds;
    /** 分析所依据的数据版本指纹。 */
    private String dataVersion;
    /** RUNNING / COMPLETED / FAILED。 */
    private String status;
    private Integer totalWindows;
    private Integer schedulableCount;
    private Integer rejectedCount;
    /** 指标摘要 JSON（冲突类型计数/受影响资源/告警）。 */
    private String summaryJson;
    /** 逐窗口分析明细 JSON（含冲突对象与时间段）。 */
    private String detailJson;
    private String errorMessage;
    private String operator;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
