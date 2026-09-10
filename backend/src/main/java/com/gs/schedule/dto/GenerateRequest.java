package com.gs.schedule.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class GenerateRequest {
    /** 传入则向该草稿追加排程；为空则新建草稿版本。 */
    private Long versionId;
    private String label;
    @NotNull(message = "排程起始时间不能为空")
    private LocalDateTime rangeStart;
    @NotNull(message = "排程结束时间不能为空")
    private LocalDateTime rangeEnd;
    private List<Long> stationIds;
    private List<Long> satelliteIds;
}
