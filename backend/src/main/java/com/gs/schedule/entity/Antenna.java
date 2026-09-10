package com.gs.schedule.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("antenna")
public class Antenna {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long stationId;
    private String code;
    private String name;
    /** 支持频段，如 "S/X"。 */
    private String band;
    /** ENABLED / DISABLED / MAINTENANCE */
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
