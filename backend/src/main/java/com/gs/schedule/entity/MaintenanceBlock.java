package com.gs.schedule.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("maintenance_block")
public class MaintenanceBlock {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long stationId;
    /** 为空表示全站封锁。 */
    private Long antennaId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String reason;
    private LocalDateTime createdAt;
}
