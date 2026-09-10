package com.gs.schedule.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务调整请求。version 为乐观锁版本号，必填，与库中不一致时返回 409。
 */
@Data
public class TaskAdjustRequest {
    private Long antennaId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer priority;
    private String remark;
    @NotNull(message = "乐观锁版本号 version 不能为空")
    private Integer version;
}
