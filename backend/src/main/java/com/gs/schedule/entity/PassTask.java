package com.gs.schedule.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pass_task")
public class PassTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long windowId;
    private Long satelliteId;
    private Long stationId;
    private Long antennaId;
    private Long versionId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer priority;
    /** DRAFT / PUBLISHED / CANCELLED。 */
    private String status;
    private String remark;

    /** 乐观锁：调整任务时必须携带，更新失败（版本不一致）返回 409。 */
    @Version
    private Integer version;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
