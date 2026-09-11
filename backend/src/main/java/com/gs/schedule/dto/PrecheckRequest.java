package com.gs.schedule.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 预检请求。时间一律 UTC；stationIds/satelliteIds 为空表示不筛选。
 * 同一组参数（归一化后）重复请求命中既有报告，不产生重复分析记录。
 */
@Data
public class PrecheckRequest {
    private LocalDateTime rangeStart;
    private LocalDateTime rangeEnd;
    private List<Long> stationIds;
    private List<Long> satelliteIds;
    /** 操作人，写入审计。 */
    private String operator;
    /** true 时按相同参数重新分析并覆盖既有报告（仍不产生重复记录）。 */
    private Boolean refresh;
}
