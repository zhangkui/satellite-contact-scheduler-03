package com.gs.schedule.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("schedule_audit")
public class ScheduleAudit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long versionId;
    private Long taskId;
    /** GENERATE / ADD / ADJUST / CANCEL / REMOVE / PUBLISH / REVISE。 */
    private String action;
    /** 调整前对象 JSON。 */
    private String beforeJson;
    /** 调整后对象 JSON。 */
    private String afterJson;
    private String detail;
    private LocalDateTime createdAt;
}
