package com.gs.schedule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gs.schedule.dto.TaskVO;
import com.gs.schedule.entity.Antenna;
import com.gs.schedule.entity.GroundStation;
import com.gs.schedule.entity.PassTask;
import com.gs.schedule.entity.Satellite;
import com.gs.schedule.mapper.AntennaMapper;
import com.gs.schedule.mapper.GroundStationMapper;
import com.gs.schedule.mapper.PassTaskMapper;
import com.gs.schedule.mapper.SatelliteMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 将任务实体装配为带资源名称的 VO（批量查询避免 N+1）。 */
@Component
@RequiredArgsConstructor
public class ViewAssembler {

    private final PassTaskMapper taskMapper;
    private final SatelliteMapper satelliteMapper;
    private final GroundStationMapper stationMapper;
    private final AntennaMapper antennaMapper;

    public List<TaskVO> tasks(Long versionId, boolean includeCancelled) {
        LambdaQueryWrapper<PassTask> w = new LambdaQueryWrapper<PassTask>()
                .eq(PassTask::getVersionId, versionId)
                .orderByAsc(PassTask::getStartTime);
        if (!includeCancelled) {
            w.ne(PassTask::getStatus, "CANCELLED");
        }
        return toVOs(taskMapper.selectList(w));
    }

    public List<TaskVO> toVOs(List<PassTask> tasks) {
        if (tasks.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, Satellite> sats = new HashMap<>();
        satelliteMapper.selectList(null).forEach(s -> sats.put(s.getId(), s));
        Map<Long, GroundStation> stations = new HashMap<>();
        stationMapper.selectList(null).forEach(s -> stations.put(s.getId(), s));
        Map<Long, Antenna> antennas = new HashMap<>();
        antennaMapper.selectList(null).forEach(a -> antennas.put(a.getId(), a));

        List<TaskVO> result = new ArrayList<>();
        for (PassTask t : tasks) {
            TaskVO vo = new TaskVO();
            vo.setId(t.getId());
            vo.setWindowId(t.getWindowId());
            vo.setVersionId(t.getVersionId());
            vo.setSatelliteId(t.getSatelliteId());
            vo.setStationId(t.getStationId());
            vo.setAntennaId(t.getAntennaId());
            vo.setStartTime(t.getStartTime());
            vo.setEndTime(t.getEndTime());
            vo.setPriority(t.getPriority());
            vo.setStatus(t.getStatus());
            vo.setRemark(t.getRemark());
            vo.setVersion(t.getVersion());
            vo.setCrossMidnight(!t.getStartTime().toLocalDate().equals(t.getEndTime().toLocalDate()));
            vo.setUpdatedAt(t.getUpdatedAt());

            Satellite sat = sats.get(t.getSatelliteId());
            if (sat != null) {
                vo.setSatelliteCode(sat.getCode());
                vo.setSatelliteName(sat.getName());
            }
            GroundStation st = stations.get(t.getStationId());
            if (st != null) {
                vo.setStationCode(st.getCode());
                vo.setStationName(st.getName());
            }
            Antenna an = antennas.get(t.getAntennaId());
            if (an != null) {
                vo.setAntennaCode(an.getCode());
                vo.setAntennaName(an.getName());
            }
            result.add(vo);
        }
        return result;
    }

    public static boolean crossesMidnight(LocalDateTime start, LocalDateTime end) {
        return !start.toLocalDate().equals(LocalDate.from(end));
    }
}
