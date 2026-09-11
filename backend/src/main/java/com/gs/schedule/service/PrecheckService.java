package com.gs.schedule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gs.schedule.common.BusinessException;
import com.gs.schedule.dto.GenerateRequest;
import com.gs.schedule.dto.GenerateResult;
import com.gs.schedule.dto.PrecheckRequest;
import com.gs.schedule.dto.PrecheckReportVO;
import com.gs.schedule.entity.Antenna;
import com.gs.schedule.entity.GroundStation;
import com.gs.schedule.entity.PassTask;
import com.gs.schedule.entity.PrecheckReport;
import com.gs.schedule.entity.Satellite;
import com.gs.schedule.entity.VisibilityWindow;
import com.gs.schedule.mapper.AntennaMapper;
import com.gs.schedule.mapper.GroundStationMapper;
import com.gs.schedule.mapper.MaintenanceBlockMapper;
import com.gs.schedule.mapper.PassTaskMapper;
import com.gs.schedule.mapper.PrecheckReportMapper;
import com.gs.schedule.mapper.SatelliteMapper;
import com.gs.schedule.mapper.VisibilityWindowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 排程预检：在不触碰任何草稿的前提下，按与自动排程相同的贪心顺序“干跑”一遍，
 * 综合可见窗口、卫星频段、启用天线、维护封锁、已有活动任务与同站时间重叠规则，输出：
 * 可排窗口数、必然落选窗口、潜在冲突类型与受影响资源。
 *
 * <p>报告按 排程范围 + 站点/卫星筛选（request_hash）幂等持久化，并记录数据版本指纹；
 * 同一参数重复请求直接返回既有报告，不产生重复分析记录。预检开始/完成/失败与
 * 用户触发生成均写入排程审计（version_id 为空，操作人与请求关联信息放在 after_json，
 * 沿用现有审计载荷语义）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrecheckService {

    private final PrecheckReportMapper reportMapper;
    private final VisibilityWindowMapper windowMapper;
    private final PassTaskMapper taskMapper;
    private final SatelliteMapper satelliteMapper;
    private final GroundStationMapper stationMapper;
    private final AntennaMapper antennaMapper;
    private final MaintenanceBlockMapper maintenanceMapper;
    private final ScheduleConflictDetector detector;
    private final ScheduleService scheduleService;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    // ============================ 执行预检（幂等） ============================

    /**
     * 执行预检。不加 @Transactional：分析失败时 FAILED 状态与失败审计仍需落库。
     * 同一参数重复请求命中 COMPLETED 报告直接返回（cached=true），不重新分析、不写审计。
     */
    public PrecheckReportVO run(PrecheckRequest req) {
        String operator = req.getOperator() != null && !req.getOperator().isBlank()
                ? req.getOperator() : "scheduler";
        // 参数校验失败同样写入预检失败审计，错误信息定位到具体参数
        try {
            validate(req);
        } catch (BusinessException ex) {
            audit.log(null, null, "PRECHECK_FAIL", null,
                    Map.of("operator", operator, "request", requestPayload(req), "error", ex.getMessage()),
                    "预检分析失败：" + ex.getMessage());
            throw ex;
        }

        List<Long> stationIds = normalizedIds(req.getStationIds());
        List<Long> satelliteIds = normalizedIds(req.getSatelliteIds());
        String hash = requestHash(req.getRangeStart(), req.getRangeEnd(), stationIds, satelliteIds);

        PrecheckReport report = reportMapper.selectOne(new LambdaQueryWrapper<PrecheckReport>()
                .eq(PrecheckReport::getRequestHash, hash));
        boolean refresh = Boolean.TRUE.equals(req.getRefresh());
        if (report != null && "COMPLETED".equals(report.getStatus()) && !refresh) {
            // 幂等命中：同一参数不重复分析、不产生新记录
            return toVO(report, true, true);
        }

        if (report == null) {
            report = new PrecheckReport();
            report.setRequestHash(hash);
            report.setRangeStart(req.getRangeStart());
            report.setRangeEnd(req.getRangeEnd());
            report.setStationIds(toJson(stationIds));
            report.setSatelliteIds(toJson(satelliteIds));
            report.setStatus("RUNNING");
            report.setTotalWindows(0);
            report.setSchedulableCount(0);
            report.setRejectedCount(0);
            report.setOperator(operator);
            reportMapper.insert(report);
        } else {
            // FAILED 重试或显式刷新：复用原记录，不产生重复行
            report.setStatus("RUNNING");
            report.setErrorMessage(null);
            report.setOperator(operator);
            reportMapper.updateById(report);
        }

        Map<String, Object> requestPayload = requestPayload(req);
        audit.log(null, null, "PRECHECK_START", null,
                Map.of("reportId", report.getId(), "operator", operator, "request", requestPayload),
                "预检分析开始（报告 #" + report.getId() + "）");

        try {
            Map<String, Object> analysis = analyze(req.getRangeStart(), req.getRangeEnd(),
                    stationIds, satelliteIds);
            report.setStatus("COMPLETED");
            report.setDataVersion((String) analysis.get("dataVersion"));
            report.setTotalWindows((Integer) analysis.get("totalWindows"));
            report.setSchedulableCount((Integer) analysis.get("schedulableCount"));
            report.setRejectedCount((Integer) analysis.get("rejectedCount"));
            report.setSummaryJson(toJson(analysis.get("summary")));
            report.setDetailJson(toJson(analysis.get("windows")));
            report.setErrorMessage(null);
            reportMapper.updateById(report);

            audit.log(null, null, "PRECHECK_DONE", null,
                    Map.of("reportId", report.getId(), "operator", operator,
                            "request", requestPayload, "summary", analysis.get("summary")),
                    "预检分析完成：可排 " + report.getSchedulableCount()
                            + " / 必然落选 " + report.getRejectedCount()
                            + " / 窗口总数 " + report.getTotalWindows());
        } catch (RuntimeException e) {
            report.setStatus("FAILED");
            report.setErrorMessage(e.getMessage());
            reportMapper.updateById(report);
            audit.log(null, null, "PRECHECK_FAIL", null,
                    Map.of("reportId", report.getId(), "operator", operator,
                            "request", requestPayload, "error", String.valueOf(e.getMessage())),
                    "预检分析失败：" + e.getMessage());
            throw e;
        }
        return toVO(report, true, false);
    }

    // ============================ 查询 ============================

    public List<PrecheckReportVO> listReports() {
        return reportMapper.selectList(new LambdaQueryWrapper<PrecheckReport>()
                        .orderByDesc(PrecheckReport::getId))
                .stream().map(r -> toVO(r, false, false)).toList();
    }

    public PrecheckReportVO getReport(Long id) {
        return toVO(requireReport(id), true, false);
    }

    // ============================ 依据预检生成草稿 ============================

    /**
     * 依据预检报告的参数生成排程草稿。始终新建草稿版本（不回传 versionId），
     * 不会改变任何既有草稿；生成动作写 PRECHECK_GEN 审计并关联报告与新版本。
     */
    public Map<String, Object> generateFromReport(Long reportId, String operator) {
        PrecheckReport report = requireReport(reportId);
        if (!"COMPLETED".equals(report.getStatus())) {
            throw new BusinessException("预检报告 #" + reportId + " 未完成（状态 "
                    + report.getStatus() + "），不能据此生成草稿");
        }
        GenerateRequest req = new GenerateRequest();
        req.setRangeStart(report.getRangeStart());
        req.setRangeEnd(report.getRangeEnd());
        req.setStationIds(parseIds(report.getStationIds()));
        req.setSatelliteIds(parseIds(report.getSatelliteIds()));
        req.setLabel("预检生成草稿（报告 #" + reportId + "）");
        GenerateResult result = scheduleService.generate(req);

        String op = operator != null && !operator.isBlank() ? operator : report.getOperator();
        audit.log(result.getVersionId(), null, "PRECHECK_GEN", null,
                Map.of("reportId", report.getId(), "operator", op,
                        "versionId", result.getVersionId(),
                        "scheduledCount", result.getScheduledCount(),
                        "rejectedCount", result.getRejectedCount()),
                "依据预检报告 #" + reportId + " 生成排程草稿 #" + result.getVersionId());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("reportId", report.getId());
        resp.put("result", result);
        return resp;
    }

    // ============================ 干跑分析 ============================

    /**
     * 与自动排程相同的候选集与贪心顺序（优先级降序、AOS 升序），但只模拟不落库：
     * DB 级硬约束复用 {@link ScheduleConflictDetector}，本轮“拟入选”任务在内存中叠加判定。
     */
    private Map<String, Object> analyze(LocalDateTime rangeStart, LocalDateTime rangeEnd,
                                        List<Long> stationIds, List<Long> satelliteIds) {
        // 候选窗口：与范围相交即纳入（跨午夜/跨日期窗口天然被覆盖），不限 AVAILABLE——
        // 被占用的窗口也要给出必然落选原因
        List<VisibilityWindow> windows = new ArrayList<>(windowMapper.selectList(new LambdaQueryWrapper<VisibilityWindow>()
                .lt(VisibilityWindow::getStartTime, rangeEnd)
                .gt(VisibilityWindow::getEndTime, rangeStart)
                .in(!stationIds.isEmpty(), VisibilityWindow::getStationId, stationIds)
                .in(!satelliteIds.isEmpty(), VisibilityWindow::getSatelliteId, satelliteIds)));

        Map<Long, Satellite> satMap = satelliteMapper.selectList(null).stream()
                .collect(Collectors.toMap(Satellite::getId, Function.identity()));
        Map<Long, GroundStation> stationMap = stationMapper.selectList(null).stream()
                .collect(Collectors.toMap(GroundStation::getId, Function.identity()));
        Map<Long, List<Antenna>> antennasByStation = antennaMapper.selectList(null).stream()
                .collect(Collectors.groupingBy(Antenna::getStationId));

        windows.sort(Comparator.comparingInt(VisibilityWindow::getPriority).reversed()
                .thenComparing(VisibilityWindow::getStartTime));

        List<Map<String, Object>> details = new ArrayList<>();
        List<PassTask> simulated = new ArrayList<>(); // 本轮干跑拟入选任务（内存态）
        int schedulable = 0;
        int crossMidnight = 0;

        for (VisibilityWindow win : windows) {
            boolean winCrossMidnight = ViewAssembler.crossesMidnight(win.getStartTime(), win.getEndTime());
            if (winCrossMidnight) {
                crossMidnight++;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("windowId", win.getId());
            detail.put("satelliteId", win.getSatelliteId());
            detail.put("stationId", win.getStationId());
            detail.put("startTime", win.getStartTime());
            detail.put("endTime", win.getEndTime());
            detail.put("priority", win.getPriority());
            detail.put("crossMidnight", winCrossMidnight);
            detail.put("windowStatus", win.getStatus());

            Satellite sat = satMap.get(win.getSatelliteId());
            GroundStation station = stationMap.get(win.getStationId());

            // 1) 禁用资源：直接判必然落选，错误信息定位到资源
            List<Map<String, Object>> conflicts = disabledResourceConflicts(win, sat, station);

            // 2) 已有活动任务占用该窗口
            if (conflicts.isEmpty() && !"AVAILABLE".equals(win.getStatus())) {
                List<PassTask> active = taskMapper.selectActiveByWindow(win.getId());
                for (PassTask t : active) {
                    conflicts.add(baseConflict("WINDOW_ALREADY_SCHEDULED",
                            "窗口[id=" + win.getId() + "]在有效版本中已有活动任务[id=" + t.getId() + "]，必然落选",
                            max(win.getStartTime(), t.getStartTime()), min(win.getEndTime(), t.getEndTime()),
                            win.getStationId(), null, taskRef(t)));
                }
            }

            // 3) 天线能力 + 4) 维护封锁/同站重叠（含本轮拟入选）
            Antenna placed = null;
            if (conflicts.isEmpty()) {
                List<Antenna> candidates = antennaCandidates(win, antennasByStation.get(win.getStationId()), sat);
                if (candidates.isEmpty()) {
                    conflicts.add(staticConflict("ANTENNA_CAPABILITY",
                            "地面站[" + stationName(station) + "]没有 ENABLED 且频段["
                                    + (sat == null ? "?" : sat.getBand()) + "]匹配的天线，窗口[id=" + win.getId() + "]必然落选",
                            win.getStationId(), null));
                } else {
                    List<Map<String, Object>> attempts = new ArrayList<>();
                    for (Antenna candidate : candidates) {
                        List<Map<String, Object>> attempt = new ArrayList<>(detector.detect(
                                null, null, null, win.getStationId(), candidate.getId(),
                                sat == null ? null : sat.getBand(), win.getStartTime(), win.getEndTime()));
                        attempt.addAll(simulatedConflicts(simulated, win, candidate));
                        if (attempt.isEmpty()) {
                            placed = candidate;
                            break;
                        }
                        attempts.addAll(attempt);
                    }
                    if (placed == null) {
                        conflicts.addAll(dedupConflicts(attempts));
                    }
                }
            }

            if (placed != null) {
                detail.put("verdict", "SCHEDULABLE");
                detail.put("antennaId", placed.getId());
                detail.put("conflicts", List.of());
                simulated.add(simulatedTask(win, placed));
                schedulable++;
            } else {
                detail.put("verdict", "REJECTED");
                detail.put("conflicts", conflicts);
            }
            details.add(detail);
        }

        Map<String, Object> summary = summarize(rangeStart, rangeEnd, windows, details,
                schedulable, crossMidnight);

        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("dataVersion", dataFingerprint());
        analysis.put("totalWindows", windows.size());
        analysis.put("schedulableCount", schedulable);
        analysis.put("rejectedCount", windows.size() - schedulable);
        analysis.put("summary", summary);
        analysis.put("windows", details);
        return analysis;
    }

    /** 汇总指标：可排/落选计数、潜在冲突类型计数、受影响资源、边界情况告警。 */
    private Map<String, Object> summarize(LocalDateTime rangeStart, LocalDateTime rangeEnd,
                                          List<VisibilityWindow> windows,
                                          List<Map<String, Object>> details,
                                          int schedulable, int crossMidnight) {
        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        Set<Long> stationIds = new LinkedHashSet<>();
        Set<Long> antennaIds = new LinkedHashSet<>();
        Set<Long> satelliteIds = new LinkedHashSet<>();
        Set<Long> maintenanceBlockIds = new LinkedHashSet<>();
        Set<Long> blockingTaskIds = new LinkedHashSet<>();

        for (Map<String, Object> detail : details) {
            if (!"REJECTED".equals(detail.get("verdict"))) {
                continue;
            }
            satelliteIds.add(((Number) detail.get("satelliteId")).longValue());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> conflicts =
                    (List<Map<String, Object>>) detail.get("conflicts");
            Set<String> typesInWindow = new LinkedHashSet<>();
            for (Map<String, Object> c : conflicts) {
                typesInWindow.add(String.valueOf(c.get("type")));
                if (c.get("stationId") != null) {
                    stationIds.add(((Number) c.get("stationId")).longValue());
                }
                if (c.get("antennaId") != null) {
                    antennaIds.add(((Number) c.get("antennaId")).longValue());
                }
                if (c.get("maintenanceBlockId") != null) {
                    maintenanceBlockIds.add(((Number) c.get("maintenanceBlockId")).longValue());
                }
                Object existing = c.get("existingTask");
                if (existing instanceof Map<?, ?> ref && ref.get("id") != null) {
                    blockingTaskIds.add(((Number) ref.get("id")).longValue());
                }
            }
            // 潜在冲突类型：按窗口去重计数（一个窗口命中多种类型各计一次）
            for (String type : typesInWindow) {
                typeCounts.merge(type, 1, Integer::sum);
            }
        }

        List<String> warnings = new ArrayList<>();
        if (windows.isEmpty()) {
            warnings.add("排程范围 [" + rangeStart + " ~ " + rangeEnd + "] 内没有可见窗口（空范围）");
        }
        boolean rangeCrossesMidnight = !rangeStart.toLocalDate().equals(rangeEnd.toLocalDate());
        if (rangeCrossesMidnight) {
            warnings.add("排程范围跨午夜（" + rangeStart.toLocalDate() + " ~ " + rangeEnd.toLocalDate()
                    + "），已按 UTC 连续区间处理");
        }
        if (crossMidnight > 0) {
            warnings.add("存在 " + crossMidnight + " 个跨日期窗口，已按半开区间 [start,end) 参与运算");
        }

        Map<String, Object> affected = new LinkedHashMap<>();
        affected.put("stationIds", new ArrayList<>(stationIds));
        affected.put("antennaIds", new ArrayList<>(antennaIds));
        affected.put("satelliteIds", new ArrayList<>(satelliteIds));
        affected.put("maintenanceBlockIds", new ArrayList<>(maintenanceBlockIds));
        affected.put("blockingTaskIds", new ArrayList<>(blockingTaskIds));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalWindows", windows.size());
        summary.put("schedulableCount", schedulable);
        summary.put("rejectedCount", windows.size() - schedulable);
        summary.put("crossMidnightWindows", crossMidnight);
        summary.put("rangeCrossesMidnight", rangeCrossesMidnight);
        summary.put("conflictTypeCounts", typeCounts);
        summary.put("affectedResources", affected);
        summary.put("warnings", warnings);
        return summary;
    }

    // ============================ 辅助方法 ============================

    private void validate(PrecheckRequest req) {
        if (req.getRangeStart() == null || req.getRangeEnd() == null) {
            throw new BusinessException("预检排程范围不能为空：rangeStart="
                    + req.getRangeStart() + ", rangeEnd=" + req.getRangeEnd());
        }
        if (!req.getRangeEnd().isAfter(req.getRangeStart())) {
            throw new BusinessException("预检结束时间必须晚于开始时间：rangeStart="
                    + req.getRangeStart() + ", rangeEnd=" + req.getRangeEnd());
        }
    }

    /** 禁用资源冲突：地面站/卫星不存在或已禁用，错误信息定位到具体资源。 */
    private List<Map<String, Object>> disabledResourceConflicts(VisibilityWindow win,
                                                                Satellite sat,
                                                                GroundStation station) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        if (station == null) {
            conflicts.add(staticConflict("RESOURCE_DISABLED",
                    "窗口[id=" + win.getId() + "]引用的地面站[id=" + win.getStationId() + "]不存在",
                    win.getStationId(), null));
        } else if (!Boolean.TRUE.equals(station.getEnabled())) {
            conflicts.add(staticConflict("RESOURCE_DISABLED",
                    "地面站[" + station.getName() + "](id=" + station.getId() + ")已禁用，窗口[id="
                            + win.getId() + "]必然落选", station.getId(), null));
        }
        if (sat == null) {
            conflicts.add(staticConflict("RESOURCE_DISABLED",
                    "窗口[id=" + win.getId() + "]引用的卫星[id=" + win.getSatelliteId() + "]不存在",
                    win.getStationId(), null));
        } else if (!Boolean.TRUE.equals(sat.getEnabled())) {
            conflicts.add(staticConflict("RESOURCE_DISABLED",
                    "卫星[" + sat.getCode() + "](id=" + sat.getId() + ")已禁用，窗口[id="
                            + win.getId() + "]必然落选", win.getStationId(), null));
        }
        return conflicts;
    }

    /** 本轮干跑已“拟入选”任务与当前窗口的同站重叠（内存态，DB 检测的补充）。 */
    private List<Map<String, Object>> simulatedConflicts(List<PassTask> simulated,
                                                         VisibilityWindow win, Antenna candidate) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (PassTask other : simulated) {
            if (!other.getStationId().equals(win.getStationId())
                    || !ScheduleConflictDetector.overlaps(win.getStartTime(), win.getEndTime(),
                    other.getStartTime(), other.getEndTime())) {
                continue;
            }
            Map<String, Object> c = baseConflict("STATION_OVERLAP",
                    "与本次预检拟入选窗口[id=" + other.getWindowId() + "]时间重叠，同一地面站禁止重叠排程",
                    max(win.getStartTime(), other.getStartTime()),
                    min(win.getEndTime(), other.getEndTime()),
                    win.getStationId(), candidate.getId(), taskRef(other));
            conflicts.add(c);
        }
        return conflicts;
    }

    private List<Antenna> antennaCandidates(VisibilityWindow win, List<Antenna> stationAntennas,
                                            Satellite sat) {
        if (stationAntennas == null) {
            return List.of();
        }
        String band = sat == null ? "" : sat.getBand();
        return stationAntennas.stream()
                .filter(a -> "ENABLED".equals(a.getStatus()))
                .filter(a -> ScheduleConflictDetector.bandSupports(a.getBand(), band))
                .sorted(Comparator.comparing((Antenna a) -> !a.getId().equals(win.getPreferredAntennaId())))
                .toList();
    }

    private PassTask simulatedTask(VisibilityWindow win, Antenna antenna) {
        PassTask t = new PassTask();
        t.setWindowId(win.getId());
        t.setSatelliteId(win.getSatelliteId());
        t.setStationId(win.getStationId());
        t.setAntennaId(antenna.getId());
        t.setStartTime(win.getStartTime());
        t.setEndTime(win.getEndTime());
        t.setPriority(win.getPriority());
        t.setStatus("DRAFT");
        return t;
    }

    /** 多副天线尝试可能产生相同的封锁/能力冲突，按 类型+时段+对象 去重。 */
    private List<Map<String, Object>> dedupConflicts(List<Map<String, Object>> conflicts) {
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> c : conflicts) {
            String key = c.get("type") + "|" + c.get("overlapStart") + "|" + c.get("overlapEnd")
                    + "|" + c.getOrDefault("maintenanceBlockId", "")
                    + "|" + String.valueOf(c.get("existingTask")).hashCode();
            unique.putIfAbsent(key, c);
        }
        return new ArrayList<>(unique.values());
    }

    private Map<String, Object> baseConflict(String type, String reason,
                                             LocalDateTime overlapStart, LocalDateTime overlapEnd,
                                             Long stationId, Long antennaId, Map<String, Object> existingTask) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", type);
        c.put("reason", reason);
        c.put("overlapStart", overlapStart);
        c.put("overlapEnd", overlapEnd);
        c.put("overlapSeconds", ChronoUnit.SECONDS.between(overlapStart, overlapEnd));
        c.put("stationId", stationId);
        if (antennaId != null) {
            c.put("antennaId", antennaId);
        }
        if (existingTask != null) {
            c.put("existingTask", existingTask);
        }
        return c;
    }

    private Map<String, Object> staticConflict(String type, String reason,
                                               Long stationId, Long antennaId) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("type", type);
        c.put("reason", reason);
        c.put("stationId", stationId);
        if (antennaId != null) {
            c.put("antennaId", antennaId);
        }
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

    /** 数据版本指纹：相关表行数 + 最近更新时间的可读摘要。 */
    private String dataFingerprint() {
        return "windows=" + windowMapper.selectCount(null) + "@" + dash(windowMapper.maxUpdatedAt())
                + ";tasks=" + taskMapper.selectCount(null) + "@" + dash(taskMapper.maxUpdatedAt())
                + ";blocks=" + maintenanceMapper.selectCount(null) + "@" + dash(maintenanceMapper.maxCreatedAt())
                + ";antennas=" + antennaMapper.selectCount(null) + "@" + dash(antennaMapper.maxUpdatedAt());
    }

    private PrecheckReport requireReport(Long id) {
        PrecheckReport report = reportMapper.selectById(id);
        if (report == null) {
            throw new BusinessException("预检报告不存在: " + id);
        }
        return report;
    }

    private PrecheckReportVO toVO(PrecheckReport r, boolean withDetails, boolean cached) {
        PrecheckReportVO vo = new PrecheckReportVO();
        vo.setId(r.getId());
        vo.setRequestHash(r.getRequestHash());
        vo.setRangeStart(r.getRangeStart());
        vo.setRangeEnd(r.getRangeEnd());
        vo.setStationIds(parseIds(r.getStationIds()));
        vo.setSatelliteIds(parseIds(r.getSatelliteIds()));
        vo.setDataVersion(r.getDataVersion());
        vo.setStatus(r.getStatus());
        vo.setTotalWindows(r.getTotalWindows());
        vo.setSchedulableCount(r.getSchedulableCount());
        vo.setRejectedCount(r.getRejectedCount());
        vo.setErrorMessage(r.getErrorMessage());
        vo.setOperator(r.getOperator());
        vo.setCreatedAt(r.getCreatedAt());
        vo.setUpdatedAt(r.getUpdatedAt());
        vo.setCached(cached);
        if (r.getSummaryJson() != null) {
            vo.setSummary(fromJson(r.getSummaryJson(), new TypeReference<>() {
            }));
        }
        if (withDetails && r.getDetailJson() != null) {
            vo.setWindows(fromJson(r.getDetailJson(), new TypeReference<>() {
            }));
        }
        return vo;
    }

    private List<Long> normalizedIds(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    private String requestHash(LocalDateTime start, LocalDateTime end,
                               List<Long> stationIds, List<Long> satelliteIds) {
        String raw = start + "|" + end + "|" + stationIds + "|" + satelliteIds;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("请求哈希计算失败", e);
        }
    }

    private Map<String, Object> requestPayload(PrecheckRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rangeStart", req.getRangeStart());
        payload.put("rangeEnd", req.getRangeEnd());
        payload.put("stationIds", normalizedIds(req.getStationIds()));
        payload.put("satelliteIds", normalizedIds(req.getSatelliteIds()));
        return payload;
    }

    private List<Long> parseIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return fromJson(json, new TypeReference<>() {
        });
    }

    private String stationName(GroundStation station) {
        return station == null ? "?" : station.getName();
    }

    private static LocalDateTime max(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDateTime min(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    private static String dash(String s) {
        return s == null ? "-" : s;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("预检结果序列化失败", e);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("预检报告 JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }
}
