package com.gs.schedule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gs.schedule.common.BusinessException;
import com.gs.schedule.dto.GenerateRequest;
import com.gs.schedule.dto.GenerateResult;
import com.gs.schedule.dto.PrecheckRequest;
import com.gs.schedule.dto.PrecheckReportVO;
import com.gs.schedule.entity.Antenna;
import com.gs.schedule.entity.GroundStation;
import com.gs.schedule.entity.MaintenanceBlock;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 预检服务单元测试：综合规则、跨午夜边界、无可用天线、已有任务占用、
 * 幂等持久化、边界参数与审计写入。Mapper 全部 Mock，冲突检测器使用真实实现。
 */
@ExtendWith(MockitoExtension.class)
class PrecheckServiceTest {

    @Mock
    private PrecheckReportMapper reportMapper;
    @Mock
    private VisibilityWindowMapper windowMapper;
    @Mock
    private PassTaskMapper taskMapper;
    @Mock
    private SatelliteMapper satelliteMapper;
    @Mock
    private GroundStationMapper stationMapper;
    @Mock
    private AntennaMapper antennaMapper;
    @Mock
    private MaintenanceBlockMapper maintenanceMapper;
    @Mock
    private ScheduleService scheduleService;
    @Mock
    private AuditService audit;

    private PrecheckService service;

    @BeforeEach
    void setUp() {
        ScheduleConflictDetector detector =
                new ScheduleConflictDetector(taskMapper, maintenanceMapper, antennaMapper);
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        service = new PrecheckService(reportMapper, windowMapper, taskMapper, satelliteMapper,
                stationMapper, antennaMapper, maintenanceMapper, detector, scheduleService,
                audit, objectMapper);
        lenient().when(reportMapper.insert(any(PrecheckReport.class))).thenAnswer(inv -> {
            ((PrecheckReport) inv.getArgument(0)).setId(1L);
            return 1;
        });
        lenient().when(windowMapper.selectCount(any())).thenReturn(3L);
        lenient().when(taskMapper.selectCount(any())).thenReturn(0L);
        lenient().when(maintenanceMapper.selectCount(any())).thenReturn(0L);
        lenient().when(antennaMapper.selectCount(any())).thenReturn(2L);
    }

    // ============================ 综合规则：维护封锁 + 频段能力 + 多天线回退 ============================

    @Test
    @SuppressWarnings("unchecked")
    void analyze_maintenanceBlockAndBandCapability() {
        GroundStation bj = station(100, "北京站", true);
        GroundStation ks = station(200, "喀什站", true);
        Antenna antA = antenna(101, 100, "S/X", "ENABLED");
        Antenna antB = antenna(102, 100, "X", "ENABLED");
        Antenna antC = antenna(201, 200, "S/X", "ENABLED");
        Satellite satS = sat(1, "S", true);
        Satellite satX = sat(2, "X", true);
        MaintenanceBlock block = block(9, 100, 101L,
                "2026-09-10T02:50:00", "2026-09-10T03:20:00", "伺服检修");
        // w1：S 频段，仅 ANT-A 可用但命中维护封锁 → 必然落选 MAINTENANCE_BLOCK
        VisibilityWindow w1 = window(1001, 1, 100,
                "2026-09-10T03:00:00", "2026-09-10T03:15:00", 5, "AVAILABLE");
        // w2：X 频段，ANT-A 被封锁但 ANT-B 可回退 → 可排（落在 ANT-B）
        VisibilityWindow w2 = window(1002, 2, 100,
                "2026-09-10T03:16:00", "2026-09-10T03:19:00", 6, "AVAILABLE");
        // w3：另一站，封锁不影响 → 可排
        VisibilityWindow w3 = window(1003, 2, 200,
                "2026-09-10T05:00:00", "2026-09-10T05:10:00", 5, "AVAILABLE");

        stubResources(List.of(satS, satX), List.of(bj, ks), List.of(antA, antB, antC));
        when(windowMapper.selectList(any())).thenReturn(List.of(w1, w2, w3));
        when(maintenanceMapper.selectList(any())).thenReturn(List.of(block));
        when(taskMapper.selectBlocking(any(), any(), any(), any())).thenReturn(List.of());

        PrecheckReportVO vo = service.run(req("2026-09-10T00:00:00", "2026-09-10T23:59:00"));

        assertEquals("COMPLETED", vo.getStatus());
        assertFalse(vo.getCached());
        Map<String, Object> summary = vo.getSummary();
        assertEquals(3, toInt(summary.get("totalWindows")));
        assertEquals(2, toInt(summary.get("schedulableCount")));
        assertEquals(1, toInt(summary.get("rejectedCount")));
        assertNotNull(vo.getDataVersion());
        assertTrue(vo.getDataVersion().contains("windows=3"));

        Map<String, Integer> typeCounts = (Map<String, Integer>) summary.get("conflictTypeCounts");
        assertEquals(1, toInt(typeCounts.get("MAINTENANCE_BLOCK")));
        assertFalse(typeCounts.containsKey("STATION_OVERLAP"));

        Map<String, List<Object>> affected =
                (Map<String, List<Object>>) summary.get("affectedResources");
        assertTrue(affected.get("maintenanceBlockIds").stream().mapToLong(this::toLong).anyMatch(id -> id == 9L));
        assertTrue(affected.get("antennaIds").stream().mapToLong(this::toLong).anyMatch(id -> id == 101L));
        assertTrue(affected.get("stationIds").stream().mapToLong(this::toLong).anyMatch(id -> id == 100L));

        Map<String, Object> d1 = detailOf(vo, 1001);
        assertEquals("REJECTED", d1.get("verdict"));
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) d1.get("conflicts");
        assertEquals("MAINTENANCE_BLOCK", conflicts.get(0).get("type"));
        assertEquals(9, toInt(conflicts.get(0).get("maintenanceBlockId")));
        assertTrue(String.valueOf(conflicts.get(0).get("reason")).contains("伺服检修"));

        Map<String, Object> d2 = detailOf(vo, 1002);
        assertEquals("SCHEDULABLE", d2.get("verdict"));
        assertEquals(102, toInt(d2.get("antennaId"))); // 回退到 ANT-B
        assertEquals("SCHEDULABLE", detailOf(vo, 1003).get("verdict"));

        // 审计：开始 + 完成
        verify(audit).log(isNull(), isNull(), eq("PRECHECK_START"), isNull(), any(), anyString());
        verify(audit).log(isNull(), isNull(), eq("PRECHECK_DONE"), isNull(), any(),
                contains("可排 2 / 必然落选 1 / 窗口总数 3"));
        verify(audit, never()).log(any(), any(), eq("PRECHECK_FAIL"), any(), any(), any());
    }

    // ============================ 跨午夜边界：两个跨日期窗口同站重叠 ============================

    @Test
    @SuppressWarnings("unchecked")
    void analyze_crossMidnightOverlap() {
        GroundStation bj = station(100, "北京站", true);
        Antenna antA = antenna(101, 100, "S/X", "ENABLED");
        Satellite satS = sat(1, "S", true);
        // 跨午夜窗口：23:40 ~ 次日 00:05（优先级 10）与 23:50 ~ 次日 00:15（优先级 6）
        VisibilityWindow w1 = window(2001, 1, 100,
                "2026-09-10T23:40:00", "2026-09-11T00:05:00", 10, "AVAILABLE");
        VisibilityWindow w2 = window(2002, 1, 100,
                "2026-09-10T23:50:00", "2026-09-11T00:15:00", 6, "AVAILABLE");

        stubResources(List.of(satS), List.of(bj), List.of(antA));
        when(windowMapper.selectList(any())).thenReturn(List.of(w1, w2));
        when(maintenanceMapper.selectList(any())).thenReturn(List.of());
        when(taskMapper.selectBlocking(any(), any(), any(), any())).thenReturn(List.of());

        PrecheckReportVO vo = service.run(req("2026-09-10T00:00:00", "2026-09-11T00:30:00"));

        Map<String, Object> summary = vo.getSummary();
        assertEquals(1, toInt(summary.get("schedulableCount")));
        assertEquals(1, toInt(summary.get("rejectedCount")));
        assertEquals(2, toInt(summary.get("crossMidnightWindows")));
        assertEquals(Boolean.TRUE, summary.get("rangeCrossesMidnight"));
        List<String> warnings = (List<String>) summary.get("warnings");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("跨午夜")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("跨日期窗口")));

        // 高优先级 w1 入选，w2 与同站拟入选任务重叠 → STATION_OVERLAP，冲突时段跨午夜
        assertEquals("SCHEDULABLE", detailOf(vo, 2001).get("verdict"));
        Map<String, Object> d2 = detailOf(vo, 2002);
        assertEquals("REJECTED", d2.get("verdict"));
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) d2.get("conflicts");
        assertEquals(1, conflicts.size());
        Map<String, Object> c = conflicts.get(0);
        assertEquals("STATION_OVERLAP", c.get("type"));
        assertEquals("2026-09-10T23:50:00", c.get("overlapStart"));
        assertEquals("2026-09-11T00:05:00", c.get("overlapEnd"));
        assertEquals(900, toInt(c.get("overlapSeconds")));
        Map<String, Object> existing = (Map<String, Object>) c.get("existingTask");
        assertEquals(2001, toInt(existing.get("windowId")));
    }

    // ============================ 无可用天线（停用 + 频段不匹配） ============================

    @Test
    @SuppressWarnings("unchecked")
    void analyze_noAvailableAntenna() {
        GroundStation bj = station(100, "北京站", true);
        Antenna disabled = antenna(101, 100, "S/X", "DISABLED");
        Antenna bandMismatch = antenna(102, 100, "X", "ENABLED");
        Satellite satS = sat(1, "S", true);
        VisibilityWindow w1 = window(3001, 1, 100,
                "2026-09-10T08:00:00", "2026-09-10T08:10:00", 5, "AVAILABLE");

        stubResources(List.of(satS), List.of(bj), List.of(disabled, bandMismatch));
        when(windowMapper.selectList(any())).thenReturn(List.of(w1));

        PrecheckReportVO vo = service.run(req("2026-09-10T00:00:00", "2026-09-10T23:59:00"));

        Map<String, Object> summary = vo.getSummary();
        assertEquals(0, toInt(summary.get("schedulableCount")));
        assertEquals(1, toInt(summary.get("rejectedCount")));
        Map<String, Integer> typeCounts = (Map<String, Integer>) summary.get("conflictTypeCounts");
        assertEquals(1, toInt(typeCounts.get("ANTENNA_CAPABILITY")));

        Map<String, Object> d1 = detailOf(vo, 3001);
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) d1.get("conflicts");
        assertEquals("ANTENNA_CAPABILITY", conflicts.get(0).get("type"));
        // 错误信息定位到资源与窗口
        assertTrue(String.valueOf(conflicts.get(0).get("reason")).contains("北京站"));
        assertTrue(String.valueOf(conflicts.get(0).get("reason")).contains("3001"));
        // 无天线可尝试，检测器不应被触发
        verify(maintenanceMapper, never()).selectList(any());
    }

    // ============================ 已有活动任务占用 ============================

    @Test
    @SuppressWarnings("unchecked")
    void analyze_existingTaskOccupation() {
        GroundStation bj = station(100, "北京站", true);
        Antenna antA = antenna(101, 100, "S/X", "ENABLED");
        Satellite satS = sat(1, "S", true);
        // w1 已被有效版本任务占用（OCCUPIED）→ WINDOW_ALREADY_SCHEDULED
        VisibilityWindow w1 = window(4001, 1, 100,
                "2026-09-10T10:00:00", "2026-09-10T10:10:00", 5, "OCCUPIED");
        // w2 AVAILABLE 但与库中已有任务时间重叠 → STATION_OVERLAP
        VisibilityWindow w2 = window(4002, 1, 100,
                "2026-09-10T10:05:00", "2026-09-10T10:12:00", 5, "AVAILABLE");
        PassTask occupying = task(55, 4001, 100, 101,
                "2026-09-10T10:00:00", "2026-09-10T10:10:00");
        PassTask blocking = task(56, 4999, 100, 101,
                "2026-09-10T10:06:00", "2026-09-10T10:20:00");

        stubResources(List.of(satS), List.of(bj), List.of(antA));
        when(windowMapper.selectList(any())).thenReturn(List.of(w1, w2));
        when(taskMapper.selectActiveByWindow(4001L)).thenReturn(List.of(occupying));
        when(taskMapper.selectBlocking(any(), any(), any(), any())).thenReturn(List.of(blocking));
        when(maintenanceMapper.selectList(any())).thenReturn(List.of());

        PrecheckReportVO vo = service.run(req("2026-09-10T00:00:00", "2026-09-10T23:59:00"));

        Map<String, Object> summary = vo.getSummary();
        assertEquals(0, toInt(summary.get("schedulableCount")));
        assertEquals(2, toInt(summary.get("rejectedCount")));
        Map<String, Integer> typeCounts = (Map<String, Integer>) summary.get("conflictTypeCounts");
        assertEquals(1, toInt(typeCounts.get("WINDOW_ALREADY_SCHEDULED")));
        assertEquals(1, toInt(typeCounts.get("STATION_OVERLAP")));

        Map<String, Object> d1 = detailOf(vo, 4001);
        List<Map<String, Object>> c1 = (List<Map<String, Object>>) d1.get("conflicts");
        assertEquals("WINDOW_ALREADY_SCHEDULED", c1.get(0).get("type"));
        Map<String, Object> ref = (Map<String, Object>) c1.get(0).get("existingTask");
        assertEquals(55, toInt(ref.get("id")));

        Map<String, Object> d2 = detailOf(vo, 4002);
        List<Map<String, Object>> c2 = (List<Map<String, Object>>) d2.get("conflicts");
        assertEquals("STATION_OVERLAP", c2.get(0).get("type"));

        Map<String, List<Object>> affected =
                (Map<String, List<Object>>) summary.get("affectedResources");
        List<Long> blockingIds = affected.get("blockingTaskIds").stream().mapToLong(this::toLong)
                .boxed().toList();
        assertTrue(blockingIds.contains(55L));
        assertTrue(blockingIds.contains(56L));
    }

    // ============================ 禁用资源 ============================

    @Test
    @SuppressWarnings("unchecked")
    void analyze_disabledResources() {
        GroundStation disabledStation = station(100, "北京站", false);
        Satellite disabledSat = sat(1, "S", false);
        Satellite okSat = sat(2, "X", true);
        VisibilityWindow w1 = window(5001, 2, 100,
                "2026-09-10T08:00:00", "2026-09-10T08:10:00", 5, "AVAILABLE");
        VisibilityWindow w2 = window(5002, 1, 100,
                "2026-09-10T09:00:00", "2026-09-10T09:10:00", 5, "AVAILABLE");

        stubResources(List.of(disabledSat, okSat), List.of(disabledStation), List.of());
        when(windowMapper.selectList(any())).thenReturn(List.of(w1, w2));

        PrecheckReportVO vo = service.run(req("2026-09-10T00:00:00", "2026-09-10T23:59:00"));

        Map<String, Object> summary = vo.getSummary();
        assertEquals(0, toInt(summary.get("schedulableCount")));
        assertEquals(2, toInt(summary.get("rejectedCount")));
        Map<String, Integer> typeCounts = (Map<String, Integer>) summary.get("conflictTypeCounts");
        assertEquals(2, toInt(typeCounts.get("RESOURCE_DISABLED")));

        List<Map<String, Object>> c1 =
                (List<Map<String, Object>>) detailOf(vo, 5001).get("conflicts");
        assertTrue(String.valueOf(c1.get(0).get("reason")).contains("北京站"));
        List<Map<String, Object>> c2 =
                (List<Map<String, Object>>) detailOf(vo, 5002).get("conflicts");
        assertTrue(c2.stream().map(c -> String.valueOf(c.get("reason")))
                .anyMatch(r -> r.contains("卫星")));
    }

    // ============================ 幂等：同参数重复请求命中既有报告 ============================

    @Test
    void run_idempotentCacheHit() {
        PrecheckReport existing = completedReport(9L);
        when(reportMapper.selectOne(any())).thenReturn(existing);

        PrecheckRequest req = req("2026-09-10T00:00:00", "2026-09-10T23:59:00");
        req.setStationIds(List.of(2L, 1L)); // 乱序筛选归一化后仍命中
        PrecheckReportVO vo = service.run(req);

        assertTrue(vo.getCached());
        assertEquals(9L, vo.getId());
        assertEquals("COMPLETED", vo.getStatus());
        // 不产生重复分析记录、不重新分析、不写审计
        verify(reportMapper, never()).insert(any(PrecheckReport.class));
        verify(reportMapper, never()).updateById(any(PrecheckReport.class));
        verifyNoInteractions(windowMapper, taskMapper, scheduleService);
        verify(audit, never()).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    void run_refreshReusesSameRecord() {
        PrecheckReport existing = completedReport(9L);
        when(reportMapper.selectOne(any())).thenReturn(existing);
        when(windowMapper.selectList(any())).thenReturn(List.of());
        when(satelliteMapper.selectList(any())).thenReturn(List.of());
        when(stationMapper.selectList(any())).thenReturn(List.of());
        when(antennaMapper.selectList(any())).thenReturn(List.of());

        PrecheckRequest req = req("2026-09-10T00:00:00", "2026-09-10T23:59:00");
        req.setRefresh(true);
        PrecheckReportVO vo = service.run(req);

        assertFalse(vo.getCached());
        assertEquals(9L, vo.getId());
        // 复用原记录更新，不产生重复行
        verify(reportMapper, never()).insert(any(PrecheckReport.class));
        verify(reportMapper, atLeastOnce()).updateById(any(PrecheckReport.class));
        verify(audit).log(isNull(), isNull(), eq("PRECHECK_START"), isNull(), any(), anyString());
        verify(audit).log(isNull(), isNull(), eq("PRECHECK_DONE"), isNull(), any(), anyString());
    }

    @Test
    void run_requestHashNormalized() {
        when(windowMapper.selectList(any())).thenReturn(List.of());
        when(satelliteMapper.selectList(any())).thenReturn(List.of());
        when(stationMapper.selectList(any())).thenReturn(List.of());
        when(antennaMapper.selectList(any())).thenReturn(List.of());

        PrecheckRequest first = req("2026-09-10T00:00:00", "2026-09-10T23:59:00");
        first.setStationIds(List.of(2L, 1L));
        first.setSatelliteIds(List.of(5L, 3L));
        service.run(first);
        PrecheckRequest second = req("2026-09-10T00:00:00", "2026-09-10T23:59:00");
        second.setStationIds(List.of(1L, 2L, 2L)); // 乱序 + 重复
        second.setSatelliteIds(List.of(3L, 5L));
        service.run(second);

        ArgumentCaptor<PrecheckReport> captor = ArgumentCaptor.forClass(PrecheckReport.class);
        verify(reportMapper, times(2)).insert(captor.capture());
        assertEquals(captor.getAllValues().get(0).getRequestHash(),
                captor.getAllValues().get(1).getRequestHash());
    }

    // ============================ 边界：非法范围 / 空范围 ============================

    @Test
    void run_rejectsInvalidRange() {
        PrecheckRequest equal = req("2026-09-10T10:00:00", "2026-09-10T10:00:00");
        BusinessException ex1 = assertThrows(BusinessException.class, () -> service.run(equal));
        assertTrue(ex1.getMessage().contains("结束时间必须晚于开始时间"));
        assertTrue(ex1.getMessage().contains("2026-09-10T10:00"));

        PrecheckRequest reversed = req("2026-09-10T23:00:00", "2026-09-10T01:00:00");
        assertThrows(BusinessException.class, () -> service.run(reversed));

        PrecheckRequest nullEnd = req("2026-09-10T00:00:00", null);
        BusinessException ex3 = assertThrows(BusinessException.class, () -> service.run(nullEnd));
        assertTrue(ex3.getMessage().contains("不能为空"));

        // 失败审计写入，且不产生报告记录
        verify(audit, times(3)).log(isNull(), isNull(), eq("PRECHECK_FAIL"), isNull(), any(),
                contains("预检分析失败"));
        verify(reportMapper, never()).insert(any(PrecheckReport.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void run_emptyRangeNoWindows() {
        when(windowMapper.selectList(any())).thenReturn(List.of());
        when(satelliteMapper.selectList(any())).thenReturn(List.of());
        when(stationMapper.selectList(any())).thenReturn(List.of());
        when(antennaMapper.selectList(any())).thenReturn(List.of());

        PrecheckReportVO vo = service.run(req("2026-09-10T00:00:00", "2026-09-10T23:59:00"));

        assertEquals("COMPLETED", vo.getStatus());
        Map<String, Object> summary = vo.getSummary();
        assertEquals(0, toInt(summary.get("totalWindows")));
        assertEquals(0, toInt(summary.get("schedulableCount")));
        assertEquals(0, toInt(summary.get("rejectedCount")));
        List<String> warnings = (List<String>) summary.get("warnings");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("空范围")));
    }

    // ============================ 依据预检生成草稿 ============================

    @Test
    void generateFromReport_createsNewDraftAndAudits() {
        PrecheckReport report = completedReport(7L);
        report.setStationIds("[100]");
        when(reportMapper.selectById(7L)).thenReturn(report);
        GenerateResult generated = new GenerateResult();
        generated.setVersionId(42L);
        generated.setScheduledCount(3);
        generated.setRejectedCount(1);
        when(scheduleService.generate(any())).thenReturn(generated);

        Map<String, Object> resp = service.generateFromReport(7L, "op1");

        assertEquals(7L, resp.get("reportId"));
        assertEquals(42L, ((GenerateResult) resp.get("result")).getVersionId());
        // 生成请求沿用报告参数，且不回传 versionId（始终新建草稿，不改变既有草稿）
        ArgumentCaptor<GenerateRequest> captor = ArgumentCaptor.forClass(GenerateRequest.class);
        verify(scheduleService).generate(captor.capture());
        GenerateRequest sent = captor.getValue();
        assertEquals(LocalDateTime.parse("2026-09-10T00:00:00"), sent.getRangeStart());
        assertEquals(List.of(100L), sent.getStationIds());
        assertNull(sent.getVersionId());
        assertTrue(sent.getLabel().contains("预检"));
        // 审计关联报告与新版本、操作人
        verify(audit).log(eq(42L), isNull(), eq("PRECHECK_GEN"), isNull(), any(),
                contains("预检报告 #7"));
    }

    @Test
    void generateFromReport_rejectsUnfinishedReport() {
        PrecheckReport failed = completedReport(8L);
        failed.setStatus("FAILED");
        when(reportMapper.selectById(8L)).thenReturn(failed);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.generateFromReport(8L, null));
        assertTrue(ex.getMessage().contains("8"));
        verifyNoInteractions(scheduleService);
    }

    // ============================ 测试辅助 ============================

    private void stubResources(List<Satellite> sats, List<GroundStation> stations,
                               List<Antenna> antennas) {
        when(satelliteMapper.selectList(any())).thenReturn(sats);
        when(stationMapper.selectList(any())).thenReturn(stations);
        when(antennaMapper.selectList(any())).thenReturn(antennas);
        Map<Long, Antenna> byId = new HashMap<>();
        antennas.forEach(a -> byId.put(a.getId(), a));
        lenient().when(antennaMapper.selectById(any())).thenAnswer(inv -> byId.get(inv.getArgument(0)));
    }

    private static PrecheckRequest req(String start, String end) {
        PrecheckRequest req = new PrecheckRequest();
        req.setRangeStart(start == null ? null : LocalDateTime.parse(start));
        req.setRangeEnd(end == null ? null : LocalDateTime.parse(end));
        req.setOperator("tester");
        return req;
    }

    private static PrecheckReport completedReport(long id) {
        PrecheckReport r = new PrecheckReport();
        r.setId(id);
        r.setRequestHash("hash-" + id);
        r.setRangeStart(LocalDateTime.parse("2026-09-10T00:00:00"));
        r.setRangeEnd(LocalDateTime.parse("2026-09-10T23:59:00"));
        r.setStationIds("[]");
        r.setSatelliteIds("[]");
        r.setStatus("COMPLETED");
        r.setTotalWindows(2);
        r.setSchedulableCount(1);
        r.setRejectedCount(1);
        r.setSummaryJson("{\"totalWindows\":2,\"schedulableCount\":1,\"rejectedCount\":1}");
        r.setDetailJson("[]");
        r.setDataVersion("windows=2@-");
        r.setOperator("tester");
        return r;
    }

    private static Satellite sat(long id, String band, boolean enabled) {
        Satellite s = new Satellite();
        s.setId(id);
        s.setCode("SAT-" + id);
        s.setName("卫星" + id);
        s.setBand(band);
        s.setEnabled(enabled);
        s.setPriority(5);
        return s;
    }

    private static GroundStation station(long id, String name, boolean enabled) {
        GroundStation s = new GroundStation();
        s.setId(id);
        s.setCode("ST-" + id);
        s.setName(name);
        s.setTimezone("Asia/Shanghai");
        s.setEnabled(enabled);
        return s;
    }

    private static Antenna antenna(long id, long stationId, String band, String status) {
        Antenna a = new Antenna();
        a.setId(id);
        a.setStationId(stationId);
        a.setCode("ANT-" + id);
        a.setName("天线" + id);
        a.setBand(band);
        a.setStatus(status);
        return a;
    }

    private static VisibilityWindow window(long id, long satId, long stationId,
                                           String start, String end, int priority, String status) {
        VisibilityWindow w = new VisibilityWindow();
        w.setId(id);
        w.setSatelliteId(satId);
        w.setStationId(stationId);
        w.setStartTime(LocalDateTime.parse(start));
        w.setEndTime(LocalDateTime.parse(end));
        w.setPriority(priority);
        w.setStatus(status);
        return w;
    }

    private static MaintenanceBlock block(long id, long stationId, Long antennaId,
                                          String start, String end, String reason) {
        MaintenanceBlock b = new MaintenanceBlock();
        b.setId(id);
        b.setStationId(stationId);
        b.setAntennaId(antennaId);
        b.setStartTime(LocalDateTime.parse(start));
        b.setEndTime(LocalDateTime.parse(end));
        b.setReason(reason);
        return b;
    }

    private static PassTask task(long id, long windowId, long stationId, long antennaId,
                                 String start, String end) {
        PassTask t = new PassTask();
        t.setId(id);
        t.setWindowId(windowId);
        t.setSatelliteId(1L);
        t.setStationId(stationId);
        t.setAntennaId(antennaId);
        t.setVersionId(1L);
        t.setStartTime(LocalDateTime.parse(start));
        t.setEndTime(LocalDateTime.parse(end));
        t.setPriority(5);
        t.setStatus("DRAFT");
        t.setVersion(0);
        return t;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailOf(PrecheckReportVO vo, long windowId) {
        return (Map<String, Object>) vo.getWindows().stream()
                .filter(d -> ((Number) ((Map<String, Object>) d).get("windowId")).longValue() == windowId)
                .findFirst().orElseThrow();
    }

    private int toInt(Object o) {
        return ((Number) o).intValue();
    }

    private long toLong(Object o) {
        return ((Number) o).longValue();
    }
}
