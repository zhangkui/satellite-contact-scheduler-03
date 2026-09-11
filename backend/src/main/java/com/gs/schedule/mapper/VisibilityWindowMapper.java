package com.gs.schedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gs.schedule.entity.VisibilityWindow;
import org.apache.ibatis.annotations.Select;

public interface VisibilityWindowMapper extends BaseMapper<VisibilityWindow> {

    /** 数据版本指纹用：窗口最近更新时间（字符串原样返回，避免时区换算）。 */
    @Select("SELECT MAX(updated_at) FROM visibility_window")
    String maxUpdatedAt();
}
