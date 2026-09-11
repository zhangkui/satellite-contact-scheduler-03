-- =====================================================================
-- 预检功能迁移脚本（仅既有数据库需要执行；全新部署已由 schema.sql 覆盖）
-- 内容：1) schedule_audit.version_id 改为可空（预检类审计无版本）
--       2) 新增 precheck_report 表
-- 执行：mysql -ugs -pgs123456 gs_schedule < db/migration-precheck.sql
-- =====================================================================
USE gs_schedule;

ALTER TABLE schedule_audit
    MODIFY COLUMN version_id BIGINT NULL COMMENT '关联排程版本；预检类审计无版本，为 NULL',
    MODIFY COLUMN action VARCHAR(16) NOT NULL COMMENT 'GENERATE/ADD/ADJUST/CANCEL/REMOVE/PUBLISH/REVISE/PRECHECK_START/PRECHECK_DONE/PRECHECK_FAIL/PRECHECK_GEN';

CREATE TABLE IF NOT EXISTS precheck_report (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    request_hash      CHAR(64)    NOT NULL COMMENT '请求参数 SHA-256（范围+筛选归一化）',
    range_start       DATETIME    NOT NULL COMMENT '排程范围起(UTC)',
    range_end         DATETIME    NOT NULL COMMENT '排程范围止(UTC)',
    station_ids       VARCHAR(255) NULL COMMENT '站点筛选 JSON 数组，空为全部',
    satellite_ids     VARCHAR(255) NULL COMMENT '卫星筛选 JSON 数组，空为全部',
    data_version      VARCHAR(255) NULL COMMENT '分析所依据的数据版本指纹',
    status            VARCHAR(16) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/COMPLETED/FAILED',
    total_windows     INT         NOT NULL DEFAULT 0 COMMENT '范围内可见窗口总数',
    schedulable_count INT         NOT NULL DEFAULT 0 COMMENT '可排窗口数',
    rejected_count    INT         NOT NULL DEFAULT 0 COMMENT '必然落选窗口数',
    summary_json      LONGTEXT    NULL COMMENT '指标摘要（冲突类型计数/受影响资源/告警）',
    detail_json       LONGTEXT    NULL COMMENT '逐窗口分析明细（含冲突对象与时间段）',
    error_message     VARCHAR(500) NULL COMMENT '失败原因',
    operator          VARCHAR(64) NOT NULL DEFAULT 'scheduler' COMMENT '触发预检的操作人',
    created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_precheck_hash (request_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='排程预检报告(幂等)';
