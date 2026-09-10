package com.gs.schedule.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gs.schedule.common.R;
import com.gs.schedule.dto.WindowVO;
import com.gs.schedule.entity.Antenna;
import com.gs.schedule.entity.GroundStation;
import com.gs.schedule.entity.MaintenanceBlock;
import com.gs.schedule.entity.Satellite;
import com.gs.schedule.entity.VisibilityWindow;
import com.gs.schedule.mapper.AntennaMapper;
import com.gs.schedule.mapper.GroundStationMapper;
import com.gs.schedule.mapper.MaintenanceBlockMapper;
import com.gs.schedule.mapper.SatelliteMapper;
import com.gs.schedule.mapper.VisibilityWindowMapper;
import com.gs.schedule.service.ResourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 基础资源与甘特图数据。 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ResourceController {

    private final SatelliteMapper satelliteMapper;
    private final GroundStationMapper stationMapper;
    private final AntennaMapper antennaMapper;
    private final VisibilityWindowMapper windowMapper;
    private final MaintenanceBlockMapper maintenanceMapper;
    private final ResourceService resourceService;

    // ---------------- 卫星 ----------------

    @GetMapping("/satellites")
    public R<List<Satellite>> satellites() {
        return R.ok(satelliteMapper.selectList(new LambdaQueryWrapper<Satellite>()
                .orderByAsc(Satellite::getId)));
    }

    @PostMapping("/satellites")
    public R<Satellite> saveSatellite(@RequestBody Satellite satellite) {
        if (satellite.getId() == null) {
            satelliteMapper.insert(satellite);
        } else {
            satelliteMapper.updateById(satellite);
        }
        return R.ok(satellite);
    }

    // ---------------- 地面站 ----------------

    @GetMapping("/stations")
    public R<List<GroundStation>> stations() {
        return R.ok(stationMapper.selectList(new LambdaQueryWrapper<GroundStation>()
                .orderByAsc(GroundStation::getId)));
    }

    @PostMapping("/stations")
    public R<GroundStation> saveStation(@RequestBody GroundStation station) {
        if (station.getId() == null) {
            stationMapper.insert(station);
        } else {
            stationMapper.updateById(station);
        }
        return R.ok(station);
    }

    // ---------------- 天线 ----------------

    @GetMapping("/antennas")
    public R<List<Antenna>> antennas(@RequestParam(required = false) Long stationId) {
        return R.ok(antennaMapper.selectList(new LambdaQueryWrapper<Antenna>()
                .eq(stationId != null, Antenna::getStationId, stationId)
                .orderByAsc(Antenna::getId)));
    }

    @PostMapping("/antennas")
    public R<Antenna> saveAntenna(@RequestBody Antenna antenna) {
        if (antenna.getId() == null) {
            antennaMapper.insert(antenna);
        } else {
            antennaMapper.updateById(antenna);
        }
        return R.ok(antenna);
    }

    // ---------------- 可见窗口 ----------------

    @GetMapping("/windows")
    public R<List<WindowVO>> windows(@RequestParam(required = false) Long stationId,
                                     @RequestParam(required = false) Long satelliteId,
                                     @RequestParam(required = false) String date) {
        LocalDate day = date != null ? LocalDate.parse(date) : LocalDate.now(java.time.ZoneOffset.UTC);
        var rangeStart = day.atStartOfDay();
        var rangeEnd = day.plusDays(1).atStartOfDay();
        List<VisibilityWindow> list = windowMapper.selectList(new LambdaQueryWrapper<VisibilityWindow>()
                .lt(VisibilityWindow::getStartTime, rangeEnd)
                .gt(VisibilityWindow::getEndTime, rangeStart)
                .eq(stationId != null, VisibilityWindow::getStationId, stationId)
                .eq(satelliteId != null, VisibilityWindow::getSatelliteId, satelliteId)
                .orderByAsc(VisibilityWindow::getStartTime));
        return R.ok(resourceService.windowVOs(list));
    }

    @PostMapping("/windows")
    public R<VisibilityWindow> saveWindow(@RequestBody VisibilityWindow window) {
        if (window.getStatus() == null) {
            window.setStatus("AVAILABLE");
        }
        if (window.getId() == null) {
            windowMapper.insert(window);
        } else {
            windowMapper.updateById(window);
        }
        return R.ok(window);
    }

    @DeleteMapping("/windows/{id}")
    public R<Void> deleteWindow(@PathVariable Long id) {
        windowMapper.deleteById(id);
        return R.ok();
    }

    // ---------------- 维护封锁 ----------------

    @GetMapping("/maintenance")
    public R<List<MaintenanceBlock>> maintenance(@RequestParam(required = false) Long stationId,
                                                 @RequestParam(required = false) String date) {
        LambdaQueryWrapper<MaintenanceBlock> qw = new LambdaQueryWrapper<MaintenanceBlock>()
                .eq(stationId != null, MaintenanceBlock::getStationId, stationId)
                .orderByAsc(MaintenanceBlock::getStartTime);
        if (date != null) {
            LocalDate day = LocalDate.parse(date);
            qw.lt(MaintenanceBlock::getStartTime, day.plusDays(1).atStartOfDay())
                    .gt(MaintenanceBlock::getEndTime, day.atStartOfDay());
        }
        return R.ok(maintenanceMapper.selectList(qw));
    }

    @PostMapping("/maintenance")
    public R<MaintenanceBlock> saveMaintenance(@RequestBody MaintenanceBlock block) {
        if (block.getId() == null) {
            maintenanceMapper.insert(block);
        } else {
            maintenanceMapper.updateById(block);
        }
        return R.ok(block);
    }

    @DeleteMapping("/maintenance/{id}")
    public R<Void> deleteMaintenance(@PathVariable Long id) {
        maintenanceMapper.deleteById(id);
        return R.ok();
    }

    // ---------------- 甘特图聚合 ----------------

    @GetMapping("/gantt")
    public R<Map<String, Object>> gantt(@RequestParam(required = false) String date,
                                        @RequestParam(required = false) Long stationId,
                                        @RequestParam(required = false) Long satelliteId) {
        LocalDate day = date != null ? LocalDate.parse(date) : LocalDate.now(java.time.ZoneOffset.UTC);
        return R.ok(resourceService.gantt(day, stationId, satelliteId));
    }
}
