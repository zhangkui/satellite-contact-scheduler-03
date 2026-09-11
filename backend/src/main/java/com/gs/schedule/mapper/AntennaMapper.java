package com.gs.schedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gs.schedule.entity.Antenna;
import org.apache.ibatis.annotations.Select;

public interface AntennaMapper extends BaseMapper<Antenna> {

    /** 数据版本指纹用：天线最近更新时间。 */
    @Select("SELECT MAX(updated_at) FROM antenna")
    String maxUpdatedAt();
}
