package com.gs.schedule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gs.schedule.common.BusinessException;
import com.gs.schedule.common.OptimisticConflictException;
import com.gs.schedule.dto.GenerateRequest;
import com.gs.schedule.dto.GenerateResult;
import com.gs.schedule.dto.TaskAdjustRequest;
import com.gs.schedule.dto.TaskCreateRequest;
import com.gs.schedule.dto.TaskVO;
import com.gs.schedule.entity.Antenna;
import com.gs.schedule.entity.PassTask;
import com.gs.schedule.entity.Satellite;
import com.gs.schedule.entity.ScheduleAudit;
import com.gs.schedule.entity.ScheduleVersion;
import com.gs.schedule.entity.VisibilityWindow;
import com.gs.schedule.mapper.AntennaMapper;
import com.gs.schedule.mapper.PassTaskMapper;
import com.gs.schedule.mapper.SatelliteMapper;
import com.gs.schedule.mapper.ScheduleAuditMapper;
import com.gs.schedule.mapper.ScheduleVersionMapper;
import com.gs.schedule.mapper.VisibilityWindowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 排程核心服务。
 * 时间一律 UTC；跨午夜窗口只是 endTime 落在次日，区间运算无需特判。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleVersionMapper versionMapper;
    private final PassTaskMapper taskMapper;
    private final VisibilityWindowMapper windowMapper;
    private final SatelliteMapper satelliteMapper;
    private final AntennaMapper antennaMapper;
    private final ScheduleAuditMapper auditMapper;
    private final ScheduleConflictDetector detector;
    private final ViewAssembler assembler;
    private final AuditService audit;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    // ============================ 自动排程 ============================

    @Transactional
    public GenerateResult generate(GenerateRequest req) {
        if (!req.getRangeEnd().isAfter(req.getRangeStart())) {
            throw new BusinessException("排程结束时间必须晚于开始时间");
        }
        ScheduleVersion version = getOrCreateDraft(req);

        // 候选可见窗口：与排程范围相交、AVAILABLE、满足筛选条件
        List<VisibilityWindow> windows = windowMapper.selectList(new LambdaQueryWrapper<VisibilityWindow>()
                .eq(VisibilityWindow::getStatus, "AVAILABLE")
                .lt(VisibilityWindow::getStartTime, req.getRangeEnd())
                .gt(VisibilityWindow::getEndTime, req.getRangeStart())
                .in(req.getStationIds() != null && !req.getStationIds().isEmpty(),
                        VisibilityWindow::getStationId, req.getStationIds())
                .in(req.getSatelliteIds() != null && !req.getSatelliteIds().isEmpty(),
                        VisibilityWindow::getSatelliteId, req.getSatelliteIds()));

        // 本版本已排窗口，重复执行生成时不会重复安排
        Set<Long> scheduledWindowIds = taskMapper.selectList(new LambdaQueryWrapper<PassTask>()
                        .eq(PassTask::getVersionId, version.getId())
                        .ne(PassTask::getStatus, "CANCELLED"))
                .stream().map(PassTask::getWindowId).collect(Collectors.toSet());

        Map<Long, Satellite> satMap = satelliteMapper.selectList(null).stream()
                .collect(Collectors.toMap(Satellite::getId, Function.identity()));
        Map<Long, List<Antenna>> antennasByStation = antennaMapper.selectList(null).stream()
                .collect(Collectors.groupingBy(Antenna::getStationId));

        // 优先级高者先排，同优先级按 AOS 先到先得（贪心）
        windows.sort(Comparator.comparingInt(VisibilityWindow::getPriority).reversed()
                .thenComparing(VisibilityWindow::getStartTime));

        List<PassTask> created = new ArrayList<>();
        List<Map<String, Object>> rejected = new ArrayList<>();

        for (VisibilityWindow win : windows) {
            if (scheduledWindowIds.contains(win.getId())) {
                continue;
            }
            Satellite sat = satMap.get(win.getSatelliteId());
            List<Antenna> candidates = antennaCandidates(win, antennasByStation.get(win.getStationId()), sat);

            List<Map<String, Object>> attemptConflicts = new ArrayList<>();
            if (candidates.isEmpty()) {
                attemptConflicts.add(Map.of(
                        "type", "ANTENNA_CAPABILITY",
                        "reason", "该地面站没有 ENABLED 且频段匹配的天线"));
            }
            PassTask placed = null;
            for (Antenna candidate : candidates) {
                List<Map<String, Object>> conflicts = detector.detect(
                        version.getId(), version.getBaseVersionId(), null,
                        win.getStationId(), candidate.getId(),
                        sat == null ? null : sat.getBand(), win.getStartTime(), win.getEndTime());
                if (conflicts.isEmpty()) {
                    placed = newTask(win, candidate, version.getId());
                    taskMapper.insert(placed);
                    refreshOccupancy(win.getId());
                    break;
                }
                attemptConflicts.addAll(conflicts);
            }

            if (placed != null) {
                created.add(placed);
                audit.log(version.getId(), placed.getId(), "GENERATE", null,
                        assembler.toVOs(List.of(placed)).get(0), "自动排程入选");
            } else {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("windowId", win.getId());
                r.put("satelliteId", win.getSatelliteId());
                r.put("stationId", win.getStationId());
                r.put("startTime", win.getStartTime());
                r.put("endTime", win.getEndTime());
                r.put("priority", win.getPriority());
                // 聚合各候选天线的尝试结果并按类型+时段去重
                r.put("conflicts", dedupConflicts(attemptConflicts));
                rejected.add(r);
            }
        }

        GenerateResult result = new GenerateResult();
        result.setVersionId(version.getId());
        result.setVersionStatus(version.getStatus());
        result.setRangeStart(version.getRangeStart());
        result.setRangeEnd(version.getRangeEnd());
        result.setScheduledCount(created.size());
        result.setRejectedCount(rejected.size());
        result.setScheduled(assembler.toVOs(created));
        result.setRejected(rejected);
        return result;
    }

    // ============================ 手工添加（冲突整体拒绝，返回冲突对象与时间段） ============================

    @Transactional
    public TaskVO addTask(TaskCreateRequest req) {
        ScheduleVersion version = requireDraft(req.getVersionId());
        VisibilityWindow win = windowMapper.selectById(req.getWindowId());
        if (win == null) {
            throw new BusinessException("可见窗口不存在: " + req.getWindowId());
        }
        Satellite sat = satelliteMapper.selectById(win.getSatelliteId());

        long dup = taskMapper.selectCount(new LambdaQueryWrapper<PassTask>()
                .eq(PassTask::getVersionId, version.getId())
                .eq(PassTask::getWindowId, win.getId())
                .ne(PassTask::getStatus, "CANCELLED"));
        if (dup > 0) {
            throw BusinessException.conflict("该窗口在当前版本中已安排任务", List.of(Map.of(
                    "type", "WINDOW_ALREADY_SCHEDULED",
                    "reason", "窗口[id=" + win.getId() + "]已存在未取消的任务",
                    "windowId", win.getId())));
        }

        LocalDateTime start = req.getStartTime() != null ? req.getStartTime() : win.getStartTime();
        LocalDateTime end = req.getEndTime() != null ? req.getEndTime() : win.getEndTime();
        if (start.isBefore(win.getStartTime()) || end.isAfter(win.getEndTime()) || !end.isAfter(start)) {
            throw new BusinessException("任务时间必须是可见窗口的非空子集：["
                    + win.getStartTime() + ", " + win.getEndTime() + "]");
        }

        Antenna antenna = resolveAntenna(req.getAntennaId(), win, sat);
        List<Map<String, Object>> conflicts = detector.detect(
                version.getId(), version.getBaseVersionId(), null,
                win.getStationId(), antenna.getId(),
                sat == null ? null : sat.getBand(), start, end);
        if (!conflicts.isEmpty()) {
            throw BusinessException.conflict("任务提交存在冲突，已拒绝（未写入任何数据）", conflicts);
        }

        PassTask task = new PassTask();
        task.setWindowId(win.getId());
        task.setSatelliteId(win.getSatelliteId());
        task.setStationId(win.getStationId());
        task.setAntennaId(antenna.getId());
        task.setVersionId(version.getId());
        task.setStartTime(start);
        task.setEndTime(end);
        task.setPriority(req.getPriority() != null ? req.getPriority() : win.getPriority());
        task.setStatus("DRAFT");
        task.setRemark(req.getRemark());
        task.setVersion(0);
        taskMapper.insert(task);
        refreshOccupancy(win.getId());
        audit.log(version.getId(), task.getId(), "ADD", null,
                assembler.toVOs(List.of(taskMapper.selectById(task.getId()))).get(0), "手工添加任务");
        return assembler.toVOs(List.of(taskMapper.selectById(task.getId()))).get(0);
    }

    // ============================ 调整（乐观锁 + 前后留痕） ============================

    @Transactional
    public TaskVO adjustTask(Long taskId, TaskAdjustRequest req) {
        PassTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在: " + taskId);
        }
        requireDraft(task.getVersionId());
        PassTask before = copyTask(task);

        // 乐观锁：客户端 version 必须与库中一致
        if (!Objects.equals(task.getVersion(), req.getVersion())) {
            throw new OptimisticConflictException(taskId, req.getVersion(), task.getVersion());
        }

        LocalDateTime start = req.getStartTime() != null ? req.getStartTime() : task.getStartTime();
        LocalDateTime end = req.getEndTime() != null ? req.getEndTime() : task.getEndTime();
        VisibilityWindow win = windowMapper.selectById(task.getWindowId());
        if (start.isBefore(win.getStartTime()) || end.isAfter(win.getEndTime()) || !end.isAfter(start)) {
            throw new BusinessException("调整后的时间必须是可见窗口的非空子集：["
                    + win.getStartTime() + ", " + win.getEndTime() + "]");
        }

        Long antennaId = req.getAntennaId() != null ? req.getAntennaId() : task.getAntennaId();
        Antenna antenna = antennaMapper.selectById(antennaId);
        if (antenna == null || !antenna.getStationId().equals(task.getStationId())) {
            throw new BusinessException("天线不存在或不属于该地面站");
        }
        Satellite sat = satelliteMapper.selectById(task.getSatelliteId());

        ScheduleVersion editingVersion = versionMapper.selectById(task.getVersionId());
        List<Map<String, Object>> conflicts = detector.detect(
                task.getVersionId(), editingVersion.getBaseVersionId(), taskId,
                task.getStationId(), antennaId,
                sat == null ? null : sat.getBand(), start, end);
        if (!conflicts.isEmpty()) {
            throw BusinessException.conflict("调整后存在冲突，已拒绝（调整未生效）", conflicts);
        }

        task.setAntennaId(antennaId);
        task.setStartTime(start);
        task.setEndTime(end);
        if (req.getPriority() != null) {
            task.setPriority(req.getPriority());
        }
        if (req.getRemark() != null) {
            task.setRemark(req.getRemark());
        }

        // @Version 拦截器：UPDATE ... WHERE id=? AND version=?，影响行数 0 即并发修改
        int rows = taskMapper.updateById(task);
        if (rows == 0) {
            PassTask current = taskMapper.selectById(taskId);
            throw new OptimisticConflictException(taskId, req.getVersion(),
                    current == null ? null : current.getVersion());
        }

        TaskVO beforeVo = assembler.toVOs(List.of(before)).get(0);
        TaskVO afterVo = assembler.toVOs(List.of(taskMapper.selectById(taskId))).get(0);
        audit.log(task.getVersionId(), taskId, "ADJUST", beforeVo, afterVo, "任务调整");
        return afterVo;
    }

    // ============================ 取消：释放窗口 ============================

    @Transactional
    public TaskVO cancelTask(Long taskId, String reason) {
        PassTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在: " + taskId);
        }
        if ("CANCELLED".equals(task.getStatus())) {
            return assembler.toVOs(List.of(task)).get(0); // 幂等
        }
        requireDraft(task.getVersionId()); // 已发布版本的任务不允许直接改动

        PassTask before = copyTask(task);
        task.setStatus("CANCELLED");
        task.setRemark(mergeRemark(task.getRemark(), "取消：" + (reason != null ? reason : "操作员取消")));
        int rows = taskMapper.updateById(task);
        if (rows == 0) {
            PassTask current = taskMapper.selectById(taskId);
            throw new OptimisticConflictException(taskId, before.getVersion(),
                    current == null ? null : current.getVersion());
        }
        refreshOccupancy(task.getWindowId()); // 无活动任务时窗口释放为 AVAILABLE
        TaskVO beforeVo = assembler.toVOs(List.of(before)).get(0);
        TaskVO afterVo = assembler.toVOs(List.of(taskMapper.selectById(taskId))).get(0);
        audit.log(task.getVersionId(), taskId, "CANCEL", beforeVo, afterVo, "任务取消并释放窗口");
        return afterVo;
    }

    /** 从草稿中彻底移除任务并释放窗口（区别于取消留痕）。 */
    @Transactional
    public void removeTask(Long taskId) {
        PassTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        requireDraft(task.getVersionId());
        taskMapper.deleteById(taskId);
        refreshOccupancy(task.getWindowId());
        audit.log(task.getVersionId(), taskId, "REMOVE",
                assembler.toVOs(List.of(task)).get(0), null, "从草稿移除任务");
    }

    // ============================ 发布（Redis 防重 + 状态条件更新，双重防重复发布） ============================

    @Transactional
    public Map<String, Object> publish(Long versionId, String operator) {
        String lockKey = "gs:publish:v" + versionId;
        Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(30));
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BusinessException(409, 4092,
                    "该版本正在发布中，请勿重复提交发布请求", Map.of("versionId", versionId));
        }
        try {
            ScheduleVersion version = versionMapper.selectById(versionId);
            if (version == null) {
                throw new BusinessException("版本不存在: " + versionId);
            }
            if (!"DRAFT".equals(version.getStatus())) {
                throw new BusinessException(409, 4092,
                        "版本已发布（v" + version.getVersionNo() + "），不能重复发布；如需变更请基于该版本创建修订版",
                        Map.of("versionId", versionId, "status", version.getStatus()));
            }

            List<TaskVO> tasks = assembler.tasks(versionId, false);
            int nextNo = versionMapper.selectNextVersionNo();
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            String snapshot;
            try {
                snapshot = objectMapper.writeValueAsString(tasks);
            } catch (Exception e) {
                throw new IllegalStateException("版本快照序列化失败", e);
            }

            // 条件更新：只有仍是 DRAFT 才能发布，兜住数据库层面的并发重复发布
            int rows;
            try {
                rows = versionMapper.update(null, new LambdaUpdateWrapper<ScheduleVersion>()
                        .eq(ScheduleVersion::getId, versionId)
                        .eq(ScheduleVersion::getStatus, "DRAFT")
                        .set(ScheduleVersion::getStatus, "PUBLISHED")
                        .set(ScheduleVersion::getVersionNo, nextNo)
                        .set(ScheduleVersion::getSnapshot, snapshot)
                        .set(ScheduleVersion::getTaskCount, tasks.size())
                        .set(ScheduleVersion::getPublishedAt, now)
                        .set(ScheduleVersion::getCreatedBy,
                                operator != null ? operator : version.getCreatedBy()));
            } catch (DuplicateKeyException e) {
                // 极端并发下 version_no 唯一键冲突
                throw new BusinessException(409, 4092, "版本号分配冲突，可能已被发布，请刷新查看",
                        Map.of("versionId", versionId));
            }
            if (rows == 0) {
                throw new BusinessException(409, 4092, "版本刚被其他操作员发布，请刷新查看",
                        Map.of("versionId", versionId));
            }

            // 草稿任务转正
            taskMapper.update(null, new LambdaUpdateWrapper<PassTask>()
                    .eq(PassTask::getVersionId, versionId)
                    .eq(PassTask::getStatus, "DRAFT")
                    .set(PassTask::getStatus, "PUBLISHED"));

            // 旧发布版本置为 SUPERSEDED
            versionMapper.update(null, new LambdaUpdateWrapper<ScheduleVersion>()
                    .eq(ScheduleVersion::getStatus, "PUBLISHED")
                    .ne(ScheduleVersion::getId, versionId)
                    .set(ScheduleVersion::getStatus, "SUPERSEDED"));

            audit.log(versionId, null, "PUBLISH", null,
                    Map.of("versionNo", nextNo, "taskCount", tasks.size(), "operator",
                            operator != null ? operator : "scheduler"),
                    "排程发布");

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("versionId", versionId);
            resp.put("versionNo", nextNo);
            resp.put("status", "PUBLISHED");
            resp.put("taskCount", tasks.size());
            resp.put("publishedAt", now);
            return resp;
        } finally {
            redis.delete(lockKey);
        }
    }

    // ============================ 基于已发布版本创建修订版（发布后只能通过新版本修订） ============================

    @Transactional
    public Map<String, Object> createRevision(Long baseVersionId) {
        ScheduleVersion base = versionMapper.selectById(baseVersionId);
        if (base == null) {
            throw new BusinessException("版本不存在: " + baseVersionId);
        }
        if ("DRAFT".equals(base.getStatus())) {
            throw new BusinessException("草稿版本无需修订，直接编辑即可");
        }

        ScheduleVersion revision = new ScheduleVersion();
        revision.setStatus("DRAFT");
        revision.setBaseVersionId(base.getId());
        revision.setLabel("基于 v" + base.getVersionNo() + " 的修订版");
        revision.setRangeStart(base.getRangeStart());
        revision.setRangeEnd(base.getRangeEnd());
        revision.setTaskCount(base.getTaskCount());
        revision.setCreatedBy("scheduler");
        versionMapper.insert(revision);

        // 深拷贝基线任务到新草稿（新 id、version=0、状态 DRAFT），原已发布版本保持不可变
        List<PassTask> baseTasks = taskMapper.selectList(new LambdaQueryWrapper<PassTask>()
                .eq(PassTask::getVersionId, base.getId())
                .ne(PassTask::getStatus, "CANCELLED")
                .orderByAsc(PassTask::getStartTime));
        for (PassTask t : baseTasks) {
            PassTask copy = copyTask(t);
            copy.setId(null);
            copy.setVersionId(revision.getId());
            copy.setStatus("DRAFT");
            copy.setVersion(0);
            copy.setCreatedAt(null);
            copy.setUpdatedAt(null);
            taskMapper.insert(copy);
        }

        audit.log(revision.getId(), null, "REVISE",
                Map.of("baseVersionId", base.getId(), "baseVersionNo", base.getVersionNo()),
                Map.of("revisionVersionId", revision.getId(), "copiedTasks", baseTasks.size()),
                "创建修订版");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("versionId", revision.getId());
        resp.put("baseVersionId", base.getId());
        resp.put("baseVersionNo", base.getVersionNo());
        resp.put("taskCount", baseTasks.size());
        resp.put("tasks", assembler.tasks(revision.getId(), true));
        return resp;
    }

    // ============================ 版本对比 ============================

    public Map<String, Object> compare(Long leftVersionId, Long rightVersionId) {
        ScheduleVersion left = versionMapper.selectById(leftVersionId);
        ScheduleVersion right = versionMapper.selectById(rightVersionId);
        if (left == null || right == null) {
            throw new BusinessException("对比的版本不存在");
        }
        Map<Long, Map<String, Object>> leftTasks = indexByWindow(loadForCompare(left));
        Map<Long, Map<String, Object>> rightTasks = indexByWindow(loadForCompare(right));

        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        List<Map<String, Object>> changed = new ArrayList<>();
        int unchanged = 0;

        for (Map.Entry<Long, Map<String, Object>> e : rightTasks.entrySet()) {
            Map<String, Object> l = leftTasks.get(e.getKey());
            if (l == null) {
                added.add(e.getValue());
            } else {
                Map<String, Object> diff = diffTask(l, e.getValue());
                if (diff.isEmpty()) {
                    unchanged++;
                } else {
                    changed.add(diff);
                }
            }
        }
        for (Map.Entry<Long, Map<String, Object>> e : leftTasks.entrySet()) {
            if (!rightTasks.containsKey(e.getKey())) {
                removed.add(e.getValue());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("left", versionMeta(left));
        result.put("right", versionMeta(right));
        result.put("summary", Map.of(
                "added", added.size(), "removed", removed.size(),
                "changed", changed.size(), "unchanged", unchanged));
        result.put("added", added);
        result.put("removed", removed);
        result.put("changed", changed);
        return result;
    }

    // ============================ 查询 ============================

    public List<TaskVO> versionTasks(Long versionId, boolean includeCancelled) {
        return assembler.tasks(versionId, includeCancelled);
    }

    public List<ScheduleAudit> audits(Long versionId) {
        return auditMapper.selectList(new LambdaQueryWrapper<ScheduleAudit>()
                .eq(ScheduleAudit::getVersionId, versionId)
                .orderByDesc(ScheduleAudit::getId));
    }

    public List<ScheduleVersion> listVersions() {
        return versionMapper.selectList(new LambdaQueryWrapper<ScheduleVersion>()
                .orderByDesc(ScheduleVersion::getId));
    }

    public ScheduleVersion getVersion(Long versionId) {
        ScheduleVersion v = versionMapper.selectById(versionId);
        if (v == null) {
            throw new BusinessException("版本不存在: " + versionId);
        }
        return v;
    }

    // ============================ 辅助方法 ============================

    private ScheduleVersion getOrCreateDraft(GenerateRequest req) {
        if (req.getVersionId() != null) {
            return requireDraft(req.getVersionId());
        }
        ScheduleVersion v = new ScheduleVersion();
        v.setStatus("DRAFT");
        v.setLabel(req.getLabel() != null ? req.getLabel() : "自动排程草稿");
        v.setRangeStart(req.getRangeStart());
        v.setRangeEnd(req.getRangeEnd());
        v.setTaskCount(0);
        v.setCreatedBy("scheduler");
        versionMapper.insert(v);
        audit.log(v.getId(), null, "GENERATE", null,
                Map.of("rangeStart", req.getRangeStart(), "rangeEnd", req.getRangeEnd()),
                "创建排程草稿");
        return v;
    }

    private ScheduleVersion requireDraft(Long versionId) {
        ScheduleVersion v = versionMapper.selectById(versionId);
        if (v == null) {
            throw new BusinessException("版本不存在: " + versionId);
        }
        if (!"DRAFT".equals(v.getStatus())) {
            throw new BusinessException(409, 4093,
                    "版本 v" + v.getVersionNo() + " 已发布（" + v.getStatus()
                            + "），内容不可修改；请基于该版本创建修订版",
                    Map.of("versionId", versionId, "status", v.getStatus(), "versionNo", v.getVersionNo()));
        }
        return v;
    }

    private List<Antenna> antennaCandidates(VisibilityWindow win, List<Antenna> stationAntennas, Satellite sat) {
        if (stationAntennas == null) {
            return List.of();
        }
        String band = sat == null ? "" : sat.getBand();
        return stationAntennas.stream()
                .filter(a -> "ENABLED".equals(a.getStatus()))
                .filter(a -> ScheduleConflictDetector.bandSupports(a.getBand(), band))
                // 首选天线优先
                .sorted(Comparator.comparing((Antenna a) -> !a.getId().equals(win.getPreferredAntennaId())))
                .toList();
    }

    private Antenna resolveAntenna(Long requestedId, VisibilityWindow win, Satellite sat) {
        List<Antenna> candidates = antennaCandidates(win,
                antennaMapper.selectList(new LambdaQueryWrapper<Antenna>()
                        .eq(Antenna::getStationId, win.getStationId())), sat);
        if (requestedId != null) {
            Antenna a = antennaMapper.selectById(requestedId);
            if (a == null || !a.getStationId().equals(win.getStationId())
                    || !"ENABLED".equals(a.getStatus())
                    || !ScheduleConflictDetector.bandSupports(a.getBand(),
                    sat == null ? "" : sat.getBand())) {
                throw BusinessException.conflict("指定天线不可用或频段不匹配", List.of(Map.of(
                        "type", "ANTENNA_CAPABILITY",
                        "reason", "天线[id=" + requestedId + "]不可用或不支持卫星频段",
                        "stationId", win.getStationId(), "antennaId", requestedId)));
            }
            return a;
        }
        if (win.getPreferredAntennaId() != null) {
            Antenna preferred = antennaMapper.selectById(win.getPreferredAntennaId());
            if (preferred != null && candidates.stream().anyMatch(a -> a.getId().equals(preferred.getId()))) {
                return preferred;
            }
        }
        if (candidates.isEmpty()) {
            throw BusinessException.conflict("该地面站没有可用且频段匹配的天线", List.of(Map.of(
                    "type", "ANTENNA_CAPABILITY",
                    "reason", "没有 ENABLED 且频段匹配的天线",
                    "stationId", win.getStationId())));
        }
        return candidates.get(0);
    }

    /** 多副天线尝试可能产生相同的封锁/能力冲突，按 类型+时段+对象 去重。 */
    private List<Map<String, Object>> dedupConflicts(List<Map<String, Object>> conflicts) {
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> c : conflicts) {
            String key = c.get("type") + "|" + c.get("overlapStart") + "|" + c.get("overlapEnd")
                    + "|" + c.getOrDefault("maintenanceBlockId", "")
                    + "|" + c.getOrDefault("existingTask", "").hashCode();
            unique.putIfAbsent(key, c);
        }
        return new ArrayList<>(unique.values());
    }

    private PassTask newTask(VisibilityWindow win, Antenna antenna, Long versionId) {        PassTask t = new PassTask();
        t.setWindowId(win.getId());
        t.setSatelliteId(win.getSatelliteId());
        t.setStationId(win.getStationId());
        t.setAntennaId(antenna.getId());
        t.setVersionId(versionId);
        t.setStartTime(win.getStartTime());
        t.setEndTime(win.getEndTime());
        t.setPriority(win.getPriority());
        t.setStatus("DRAFT");
        t.setVersion(0);
        return t;
    }

    /**
     * 重新计算窗口占用：非 SUPERSEDED 版本（草稿/当前发布）中存在未取消任务即 OCCUPIED，
     * 否则释放为 AVAILABLE——任务取消后窗口立即可被再次排程。
     */
    private void refreshOccupancy(Long windowId) {
        long active = taskMapper.countActiveByWindow(windowId);
        VisibilityWindow update = new VisibilityWindow();
        update.setId(windowId);
        update.setStatus(active > 0 ? "OCCUPIED" : "AVAILABLE");
        windowMapper.updateById(update);
    }

    private String mergeRemark(String oldRemark, String append) {
        if (oldRemark == null || oldRemark.isBlank()) {
            return append;
        }
        return oldRemark + " | " + append;
    }

    private PassTask copyTask(PassTask t) {
        PassTask c = new PassTask();
        c.setId(t.getId());
        c.setWindowId(t.getWindowId());
        c.setSatelliteId(t.getSatelliteId());
        c.setStationId(t.getStationId());
        c.setAntennaId(t.getAntennaId());
        c.setVersionId(t.getVersionId());
        c.setStartTime(t.getStartTime());
        c.setEndTime(t.getEndTime());
        c.setPriority(t.getPriority());
        c.setStatus(t.getStatus());
        c.setRemark(t.getRemark());
        c.setVersion(t.getVersion());
        c.setCreatedAt(t.getCreatedAt());
        c.setUpdatedAt(t.getUpdatedAt());
        return c;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadForCompare(ScheduleVersion v) {
        if (v.getSnapshot() != null && !v.getSnapshot().isBlank()) {
            try {
                return objectMapper.readValue(v.getSnapshot(), new TypeReference<>() {
                });
            } catch (Exception e) {
                log.warn("版本快照解析失败 versionId={}, 回退实时查询", v.getId());
            }
        }
        return assembler.tasks(v.getId(), false).stream()
                .map(t -> (Map<String, Object>) objectMapper.convertValue(t, Map.class))
                .collect(Collectors.toList());
    }

    private Map<Long, Map<String, Object>> indexByWindow(List<Map<String, Object>> tasks) {
        Map<Long, Map<String, Object>> m = new LinkedHashMap<>();
        for (Map<String, Object> t : tasks) {
            Object w = t.get("windowId");
            if (w != null) {
                m.put(((Number) w).longValue(), t);
            }
        }
        return m;
    }

    private Map<String, Object> versionMeta(ScheduleVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("versionId", v.getId());
        m.put("versionNo", v.getVersionNo());
        m.put("status", v.getStatus());
        m.put("label", v.getLabel());
        m.put("publishedAt", v.getPublishedAt());
        m.put("taskCount", v.getTaskCount());
        return m;
    }

    private Map<String, Object> diffTask(Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        for (String field : List.of("startTime", "endTime", "antennaId", "antennaCode",
                "priority", "status", "remark")) {
            Object lv = left.get(field);
            Object rv = right.get(field);
            if (!Objects.equals(String.valueOf(lv), String.valueOf(rv))) {
                before.put(field, lv);
                after.put(field, rv);
            }
        }
        if (before.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("windowId", right.get("windowId"));
        d.put("taskId", right.get("id"));
        d.put("satelliteCode", right.get("satelliteCode"));
        d.put("stationCode", right.get("stationCode"));
        d.put("before", before);
        d.put("after", after);
        return d;
    }
}
