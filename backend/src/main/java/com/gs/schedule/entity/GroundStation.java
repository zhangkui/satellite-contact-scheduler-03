package com.gs.schedule.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ground_station")
public class GroundStation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String region;
    /** IANA 时区，如 Asia/Shanghai，仅用于前端展示；存储一律 UTC。 */
    private String timezone;
    private Double longitude;
    private Double latitude;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
