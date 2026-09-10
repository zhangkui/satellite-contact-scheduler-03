package com.gs.schedule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gs.schedule.entity.ScheduleAudit;
import com.gs.schedule.mapper.ScheduleAuditMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 排程调整审计：记录每次 GENERATE/ADD/ADJUST/CANCEL/PUBLISH/REVISE 前后的对象快照。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final ScheduleAuditMapper auditMapper;
    private final ObjectMapper objectMapper;

    public void log(Long versionId, Long taskId, String action, Object before, Object after, String detail) {
        ScheduleAudit audit = new ScheduleAudit();
        audit.setVersionId(versionId);
        audit.setTaskId(taskId);
        audit.setAction(action);
        audit.setBeforeJson(toJson(before));
        audit.setAfterJson(toJson(after));
        audit.setDetail(detail);
        auditMapper.insert(audit);
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("审计对象序列化失败: {}", e.getMessage());
            return null;
        }
    }
}
