package com.gs.schedule.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 任务 + 资源名称的聚合视图，供甘特图与列表使用。 */
@Data
public class TaskVO {
    private Long id;
    private Long windowId;
    private Long versionId;
    private Long satelliteId;
    private String satelliteCode;
    private String satelliteName;
    private Long stationId;
    private String stationCode;
    private String stationName;
    private Long antennaId;
    private String antennaCode;
    private String antennaName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer priority;
    private String status;
    private String remark;
    private Integer version;
    private Boolean crossMidnight;
    private LocalDateTime updatedAt;
}
