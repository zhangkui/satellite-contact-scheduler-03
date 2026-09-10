package com.gs.schedule.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("schedule_version")
public class ScheduleVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 发布后分配的递增版本号；草稿期间为空。 */
    private Integer versionNo;
    /** DRAFT / PUBLISHED / SUPERSEDED。 */
    private String status;
    /** 修订所基于的已发布版本。 */
    private Long baseVersionId;
    private String label;
    private LocalDateTime rangeStart;
    private LocalDateTime rangeEnd;
    /** 发布时刻的任务全量 JSON 快照，用于版本对比与留档。 */
    private String snapshot;
    private Integer taskCount;
    private String createdBy;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
