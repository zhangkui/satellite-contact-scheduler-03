package com.gs.schedule.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("visibility_window")
public class VisibilityWindow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long satelliteId;
    private Long stationId;
    private Long preferredAntennaId;
    /** AOS，UTC。 */
    private LocalDateTime startTime;
    /** LOS，UTC；跨午夜窗口即 endTime 落在次日，无需特殊存储。 */
    private LocalDateTime endTime;
    private Integer priority;
    private Double maxElevation;
    /** AVAILABLE / OCCUPIED。 */
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
