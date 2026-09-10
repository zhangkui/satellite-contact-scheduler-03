package com.gs.schedule.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 可见窗口 + 资源名称视图。 */
@Data
public class WindowVO {
    private Long id;
    private Long satelliteId;
    private String satelliteCode;
    private String satelliteName;
    private String satelliteBand;
    private Long stationId;
    private String stationCode;
    private String stationName;
    private Long preferredAntennaId;
    private String preferredAntennaCode;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer priority;
    private Double maxElevation;
    private String status;
    private Boolean crossMidnight;
}
