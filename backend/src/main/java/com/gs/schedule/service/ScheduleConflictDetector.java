package com.gs.schedule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gs.schedule.entity.Antenna;
import com.gs.schedule.entity.MaintenanceBlock;
import com.gs.schedule.entity.PassTask;
import com.gs.schedule.mapper.AntennaMapper;
import com.gs.schedule.mapper.MaintenanceBlockMapper;
import com.gs.schedule.mapper.PassTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 排程冲突检测。硬约束：
 * <ol>
 *   <li><b>同一地面站任务时间不得重叠</b>（即使占用不同天线也禁止；半开区间 [start,end)，
 *       跨午夜窗口只是普通的起止时刻，天然支持）。</li>
 *   <li>任务时段不得落入该天线的维护封锁或全站封锁。</li>
 *   <li>天线必须 ENABLED 且频段能力覆盖卫星频段，且属于该地面站。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class ScheduleConflictDetector {

    private final PassTaskMapper taskMapper;
    private final MaintenanceBlockMapper maintenanceMapper;
    private final AntennaMapper antennaMapper;

    /**
     * 检测一次“拟提交任务”的全部硬冲突。
     *
     * @param selfVersionId 当前编辑版本（仅用于日志/上下文，自身任务默认参与检测）
     * @param baseVersionId 修订场景下的基线版本（这些任务已拷入修订草稿，不视为外部占用）；无则传 null
     * @param excludeTaskId 调整任务时排除自身
     */
    public List<Map<String, Object>> detect(Long selfVersionId,
                                            Long baseVersionId,
                                            Long excludeTaskId,
                                            Long stationId,
                                            Long antennaId,
                                            String satelliteBand,
                                            LocalDateTime start,
                                            LocalDateTime end) {
        List<Map<String, Object>> conflicts = new ArrayList<>();

        if (start == null || end == null || !end.isAfter(start)) {
            conflicts.add(staticConflict("INVALID_TIME_RANGE", stationId, antennaId,
                    "任务时间区间非法（结束时间必须晚于开始时间）"));
            return conflicts;
        }

        Antenna antenna = antennaMapper.selectById(antennaId);
        if (antenna == null || !antenna.getStationId().equals(stationId)) {
            conflicts.add(staticConflict("ANTENNA_CAPABILITY", stationId, antennaId,
                    "所选天线不存在或不属于该地面站"));
            return conflicts;
        }
        if (!"ENABLED".equals(antenna.getStatus())) {
            conflicts.add(staticConflict("ANTENNA_CAPABILITY", stationId, antennaId,
                    "天线[" + antenna.getCode() + "]当前状态为 " + antenna.getStatus() + "，不可安排任务"));
        }
        if (!bandSupports(antenna.getBand(), satelliteBand)) {
            conflicts.add(staticConflict("ANTENNA_CAPABILITY", stationId, antennaId,
                    "天线[" + antenna.getCode() + "]频段[" + antenna.getBand()
                            + "]不支持卫星频段[" + satelliteBand + "]"));
        }

        // 1) 同站时间重叠（无论占用哪副天线，一律硬冲突）
        //    跨有效版本检测：同版本已放置任务 + 其它草稿 + 当前发布版本均阻塞；
        //    调整时排除任务自身；修订时排除基线版本（其任务已拷入本草稿）
        List<PassTask> nearby = taskMapper.selectBlocking(stationId, baseVersionId, start, end).stream()
                .filter(t -> excludeTaskId == null || !excludeTaskId.equals(t.getId()))
                .toList();

        for (PassTask other : nearby) {
            LocalDateTime overlapStart = start.isAfter(other.getStartTime()) ? start : other.getStartTime();
            LocalDateTime overlapEnd = end.isBefore(other.getEndTime()) ? end : other.getEndTime();
            Map<String, Object> c = baseConflict("STATION_OVERLAP",
                    "与地面站已有任务[id=" + other.getId() + "]时间重叠，同一地面站禁止重叠排程",
                    overlapStart, overlapEnd);
            c.put("stationId", stationId);
            c.put("antennaId", antennaId);
            c.put("existingTask", taskRef(other));
            conflicts.add(c);
        }

        // 2) 维护封锁：命中本天线封锁或全站封锁
        List<MaintenanceBlock> blocks = maintenanceMapper.selectList(new LambdaQueryWrapper<MaintenanceBlock>()
                .eq(MaintenanceBlock::getStationId, stationId)
                .lt(MaintenanceBlock::getStartTime, end)
                .gt(MaintenanceBlock::getEndTime, start));
        for (MaintenanceBlock block : blocks) {
            if (block.getAntennaId() != null && !block.getAntennaId().equals(antennaId)) {
                continue;
            }
            LocalDateTime overlapStart = start.isAfter(block.getStartTime()) ? start : block.getStartTime();
            LocalDateTime overlapEnd = end.isBefore(block.getEndTime()) ? end : block.getEndTime();
            Map<String, Object> c = baseConflict("MAINTENANCE_BLOCK",
                    "命中维护封锁" + (block.getAntennaId() == null ? "（全站封锁）" : "")
                            + (block.getReason() != null ? "：" + block.getReason() : ""),
                    overlapStart, overlapEnd);
            c.put("stationId", stationId);
            c.put("antennaId", antennaId);
            c.put("maintenanceBlockId", block.getId());
            conflicts.add(c);
        }

        return conflicts;
    }

    /** 两区间是否相交（半开区间，跨午夜无特殊处理）。 */
    public static boolean overlaps(LocalDateTime s1, LocalDateTime e1, LocalDateTime s2, LocalDateTime e2) {
        return s1.isBefore(e2) && s2.isBefore(e1);
    }

    /** 天线能力是否覆盖卫星频段："S/X" 支持 "S"、"X"、"S/X"。 */
    public static boolean bandSupports(String antennaBand, String satBand) {
        if (antennaBand == null || satBand == null) {
            return false;
        }
        if (satBand.contains("/")) {
            for (String b : satBand.split("/")) {
                if (!antennaBand.contains(b.trim())) {
                    return false;
                }
            }
            return true;
        }
        return antennaBand.contains(satBand.trim());
    }

    private Map<String, Object> baseConflict(String type, String reason,
                                             LocalDateTime overlapStart, LocalDateTime overlapEnd) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", type);
        c.put("reason", reason);
        c.put("overlapStart", overlapStart);
        c.put("overlapEnd", overlapEnd);
        c.put("overlapSeconds", ChronoUnit.SECONDS.between(overlapStart, overlapEnd));
        return c;
    }

    private Map<String, Object> staticConflict(String type, Long stationId, Long antennaId, String reason) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", type);
        c.put("reason", reason);
        c.put("stationId", stationId);
        c.put("antennaId", antennaId);
        return c;
    }

    private Map<String, Object> taskRef(PassTask t) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("id", t.getId());
        ref.put("windowId", t.getWindowId());
        ref.put("satelliteId", t.getSatelliteId());
        ref.put("stationId", t.getStationId());
        ref.put("antennaId", t.getAntennaId());
        ref.put("startTime", t.getStartTime());
        ref.put("endTime", t.getEndTime());
        ref.put("priority", t.getPriority());
        ref.put("status", t.getStatus());
        return ref;
    }
}
