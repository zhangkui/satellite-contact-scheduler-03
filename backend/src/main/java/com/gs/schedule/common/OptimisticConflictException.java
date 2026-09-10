package com.gs.schedule.common;

/**
 * 乐观锁冲突：任务更新时携带的 version 与库中不一致（已被他人修改）。
 */
public class OptimisticConflictException extends BusinessException {
    public OptimisticConflictException(Long taskId, Integer clientVersion, Integer dbVersion) {
        super(409, 4091,
                "任务[id=" + taskId + "]已被其他操作员修改（版本 v" + clientVersion + " → 库中 v" + dbVersion
                        + "），请刷新后重试",
                java.util.Map.of("taskId", taskId,
                        "clientVersion", clientVersion,
                        "dbVersion", dbVersion));
    }
}
