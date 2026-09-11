package com.gs.schedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gs.schedule.entity.MaintenanceBlock;
import org.apache.ibatis.annotations.Select;

public interface MaintenanceBlockMapper extends BaseMapper<MaintenanceBlock> {

    /** 数据版本指纹用：封锁最近创建时间（表无 updated_at 列）。 */
    @Select("SELECT MAX(created_at) FROM maintenance_block")
    String maxCreatedAt();
}
