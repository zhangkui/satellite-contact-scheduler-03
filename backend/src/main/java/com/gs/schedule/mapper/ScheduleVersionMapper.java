package com.gs.schedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gs.schedule.entity.ScheduleVersion;
import org.apache.ibatis.annotations.Select;

public interface ScheduleVersionMapper extends BaseMapper<ScheduleVersion> {

    /** 下一个发布版本号（已发布版本号递增，草稿 version_no 为空）。 */
    @Select("SELECT COALESCE(MAX(version_no), 0) + 1 FROM schedule_version WHERE version_no IS NOT NULL")
    Integer selectNextVersionNo();
}
