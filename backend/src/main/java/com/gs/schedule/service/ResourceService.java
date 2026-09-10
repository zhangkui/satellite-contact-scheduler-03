package com.gs.schedule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gs.schedule.dto.WindowVO;
import com.gs.schedule.entity.Antenna;
import com.gs.schedule.entity.GroundStation;
import com.gs.schedule.entity.MaintenanceBlock;
import com.gs.schedule.entity.Satellite;
import com.gs.schedule.entity.ScheduleVersion;
import com.gs.schedule.entity.VisibilityWindow;
import com.gs.schedule.mapper.AntennaMapper;
import com.gs.schedule.mapper.GroundStationMapper;
import com.gs.schedule.mapper.MaintenanceBlockMapper;
import com.gs.schedule.mapper.SatelliteMapper;
import com.gs.schedule.mapper.ScheduleVersionMapper;
import com.gs.schedule.mapper.VisibilityWindowMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 资源查询（卫星/地面站/天线/窗口/维护）与甘特图数据聚合。 */
@Service
@RequiredArgsConstructor
public class ResourceService {

    private final SatelliteMapper satelliteMapper;
    private final GroundStationMapper stationMapper;
    private final AntennaMapper antennaMapper;
    private final VisibilityWindowMapper windowMapper;
    private final MaintenanceBlockMapper maintenanceMapper;
    private final ScheduleVersionMapper versionMapper;
    private final ViewAssembler assembler;

    public List<WindowVO> windowVOs(List<VisibilityWindow> windows) {
        if (windows.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, Satellite> sats = satelliteMapper.selectList(null).stream()
                .collect(Collectors.toMap(Satellite::getId, s -> s));
        Map<Long, GroundStation> stations = stationMapper.selectList(null).stream()
                .collect(Collectors.toMap(GroundStation::getId, s -> s));
        Map<Long, Antenna> antennas = antennaMapper.selectList(null).stream()
                .collect(Collectors.toMap(Antenna::getId, a -> a, (a, b) -> a));

        List<WindowVO> result = new ArrayList<>();
        for (VisibilityWindow w : windows) {
            WindowVO vo = new WindowVO();
            vo.setId(w.getId());
            vo.setSatelliteId(w.getSatelliteId());
            vo.setStationId(w.getStationId());
            vo.setPreferredAntennaId(w.getPreferredAntennaId());
            vo.setStartTime(w.getStartTime());
            vo.setEndTime(w.getEndTime());
            vo.setPriority(w.getPriority());
            vo.setMaxElevation(w.getMaxElevation());
            vo.setStatus(w.getStatus());
            vo.setCrossMidnight(ViewAssembler.crossesMidnight(w.getStartTime(), w.getEndTime()));
            Satellite sat = sats.get(w.getSatelliteId());
            if (sat != null) {
                vo.setSatelliteCode(sat.getCode());
                vo.setSatelliteName(sat.getName());
                vo.setSatelliteBand(sat.getBand());
            }
            GroundStation st = stations.get(w.getStationId());
            if (st != null) {
                vo.setStationCode(st.getCode());
                vo.setStationName(st.getName());
            }
            Antenna an = antennas.get(w.getPreferredAntennaId());
            if (an != null) {
                vo.setPreferredAntennaCode(an.getCode());
            }
            result.add(vo);
        }
        return result;
    }

    /**
     * 甘特图聚合数据。
     *
     * @param date UTC 日期（yyyy-MM-dd），返回该日 00:00–24:00 范围；跨午夜窗口只要与范围相交即返回
     */
    public Map<String, Object> gantt(LocalDate date, Long stationId, Long satelliteId) {
        LocalDateTime rangeStart = date.atStartOfDay();
        LocalDateTime rangeEnd = date.plusDays(1).atStartOfDay();

        List<VisibilityWindow> windows = windowMapper.selectList(new LambdaQueryWrapper<VisibilityWindow>()
                .lt(VisibilityWindow::getStartTime, rangeEnd)
                .gt(VisibilityWindow::getEndTime, rangeStart)
                .eq(stationId != null, VisibilityWindow::getStationId, stationId)
                .eq(satelliteId != null, VisibilityWindow::getSatelliteId, satelliteId)
                .orderByAsc(VisibilityWindow::getStartTime));

        List<MaintenanceBlock> maintenance = maintenanceMapper.selectList(
                new LambdaQueryWrapper<MaintenanceBlock>()
                        .lt(MaintenanceBlock::getStartTime, rangeEnd)
                        .gt(MaintenanceBlock::getEndTime, rangeStart)
                        .eq(stationId != null, MaintenanceBlock::getStationId, stationId)
                        .orderByAsc(MaintenanceBlock::getStartTime));

        ScheduleVersion published = latestVersion("PUBLISHED");
        ScheduleVersion draft = latestVersion("DRAFT");

        Map<String, Object> data = new HashMap<>();
        data.put("rangeStart", rangeStart);
        data.put("rangeEnd", rangeEnd);
        data.put("stations", stationMapper.selectList(new LambdaQueryWrapper<GroundStation>()
                .eq(stationId != null, GroundStation::getId, stationId)
                .orderByAsc(GroundStation::getId)));
        data.put("antennas", antennaMapper.selectList(new LambdaQueryWrapper<Antenna>()
                .eq(stationId != null, Antenna::getStationId, stationId)));
        data.put("windows", windowVOs(windows));
        data.put("maintenance", maintenance);
        data.put("publishedVersion", published);
        data.put("draftVersion", draft);
        data.put("publishedTasks", published == null ? List.of()
                : filterTasks(assembler.tasks(published.getId(), true), stationId, satelliteId));
        data.put("draftTasks", draft == null ? List.of()
                : filterTasks(assembler.tasks(draft.getId(), true), stationId, satelliteId));
        return data;
    }

    private List<com.gs.schedule.dto.TaskVO> filterTasks(List<com.gs.schedule.dto.TaskVO> tasks,
                                                         Long stationId, Long satelliteId) {
        return tasks.stream()
                .filter(t -> stationId == null || stationId.equals(t.getStationId()))
                .filter(t -> satelliteId == null || satelliteId.equals(t.getSatelliteId()))
                .collect(Collectors.toList());
    }

    private ScheduleVersion latestVersion(String status) {
        List<ScheduleVersion> list = versionMapper.selectList(new LambdaQueryWrapper<ScheduleVersion>()
                .eq(ScheduleVersion::getStatus, status)
                .orderByDesc(ScheduleVersion::getId)
                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }
}
