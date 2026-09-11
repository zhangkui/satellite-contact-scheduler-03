package com.gs.schedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gs.schedule.entity.PassTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface PassTaskMapper extends BaseMapper<PassTask> {

    /**
     * 窗口在“有效版本”（DRAFT / PUBLISHED，不含已被取代的 SUPERSEDED）中的活动任务数。
     * 为 0 时窗口视为已释放。
     */
    @Select("""
            SELECT COUNT(*) FROM pass_task t
            JOIN schedule_version v ON t.version_id = v.id
            WHERE t.window_id = #{windowId}
              AND t.status <> 'CANCELLED'
              AND v.status <> 'SUPERSEDED'
            """)
    long countActiveByWindow(@Param("windowId") Long windowId);

    /**
     * 查询与 [start,end) 时间相交、会对排程形成阻塞的同站任务：
     * 包含当前版本自身任务（调整时由调用方按 taskId 排除自身）、其它草稿与当前发布版本，
     * 保证“同一地面站禁止重叠”跨版本生效；修订场景下排除所基于的基线版本
     * （基线内容已被拷贝进修订草稿自身），SUPERSEDED 版本不阻塞。
     */
    @Select("""
            SELECT t.* FROM pass_task t
            JOIN schedule_version v ON t.version_id = v.id
            WHERE t.station_id = #{stationId}
              AND t.status <> 'CANCELLED'
              AND v.status <> 'SUPERSEDED'
              AND (#{baseVersionId} IS NULL OR t.version_id <> #{baseVersionId})
              AND t.start_time < #{end}
              AND t.end_time > #{start}
            """)
    List<PassTask> selectBlocking(@Param("stationId") Long stationId,
                                  @Param("baseVersionId") Long baseVersionId,
                                  @Param("start") LocalDateTime start,
                                  @Param("end") LocalDateTime end);

    /**
     * 窗口在“有效版本”（DRAFT / PUBLISHED，不含 SUPERSEDED）中的活动任务，
     * 预检用于定位 WINDOW_ALREADY_SCHEDULED 的占用任务。
     */
    @Select("""
            SELECT t.* FROM pass_task t
            JOIN schedule_version v ON t.version_id = v.id
            WHERE t.window_id = #{windowId}
              AND t.status <> 'CANCELLED'
              AND v.status <> 'SUPERSEDED'
            """)
    List<PassTask> selectActiveByWindow(@Param("windowId") Long windowId);

    /** 数据版本指纹用：任务最近更新时间。 */
    @Select("SELECT MAX(updated_at) FROM pass_task")
    String maxUpdatedAt();
}
