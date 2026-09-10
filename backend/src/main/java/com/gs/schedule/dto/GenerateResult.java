package com.gs.schedule.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class GenerateResult {
    private Long versionId;
    private String versionStatus;
    private LocalDateTime rangeStart;
    private LocalDateTime rangeEnd;
    private int scheduledCount;
    private int rejectedCount;
    private List<TaskVO> scheduled;
    /** 落选窗口：含原因与冲突对象/时间段。 */
    private List<Map<String, Object>> rejected;
}
