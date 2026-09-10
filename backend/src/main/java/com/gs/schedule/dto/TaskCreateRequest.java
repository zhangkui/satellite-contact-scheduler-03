package com.gs.schedule.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 手工提交一个过站任务。发生重叠/封锁时整体拒绝并返回 409 冲突明细。
 */
@Data
public class TaskCreateRequest {
    @NotNull(message = "版本不能为空")
    private Long versionId;
    @NotNull(message = "可见窗口不能为空")
    private Long windowId;
    /** 为空时使用窗口首选天线/自动选择。 */
    private Long antennaId;
    /** 为空时使用窗口完整起止时间。 */
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer priority;
    private String remark;
}
