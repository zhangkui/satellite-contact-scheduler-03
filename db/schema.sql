-- =====================================================================
-- 卫星地面站过站窗口排程系统 - 数据库结构
-- 所有时间字段均以 UTC 存储（DATETIME），跨午夜窗口表现为普通的起止时刻
-- =====================================================================
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS satellite (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    code         VARCHAR(32)  NOT NULL COMMENT '卫星编号',
    name         VARCHAR(64)  NOT NULL COMMENT '卫星名称',
    band         VARCHAR(16)  NOT NULL DEFAULT 'S' COMMENT '通信频段 S/X/SX',
    orbit_type   VARCHAR(32)  NOT NULL DEFAULT 'LEO' COMMENT '轨道类型',
    priority     INT          NOT NULL DEFAULT 5 COMMENT '默认任务优先级 1-10',
    enabled      TINYINT(1)   NOT NULL DEFAULT 1,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_satellite_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='卫星';

CREATE TABLE IF NOT EXISTS ground_station (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    code         VARCHAR(32)  NOT NULL COMMENT '地面站编号',
    name         VARCHAR(64)  NOT NULL COMMENT '地面站名称',
    region       VARCHAR(64)  NULL COMMENT '所在地区',
    timezone     VARCHAR(64)  NOT NULL DEFAULT 'UTC' COMMENT 'IANA 时区，仅用于前端展示',
    longitude    DOUBLE       NULL,
    latitude     DOUBLE       NULL,
    enabled      TINYINT(1)   NOT NULL DEFAULT 1,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_station_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='地面站';

CREATE TABLE IF NOT EXISTS antenna (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    station_id   BIGINT       NOT NULL,
    code         VARCHAR(32)  NOT NULL COMMENT '天线编号',
    name         VARCHAR(64)  NOT NULL,
    band         VARCHAR(16)  NOT NULL DEFAULT 'S/X' COMMENT '支持频段',
    status       VARCHAR(16)  NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED/MAINTENANCE',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_antenna_station_code (station_id, code),
    CONSTRAINT fk_antenna_station FOREIGN KEY (station_id) REFERENCES ground_station (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='天线资源';

CREATE TABLE IF NOT EXISTS visibility_window (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    satellite_id        BIGINT      NOT NULL,
    station_id          BIGINT      NOT NULL,
    preferred_antenna_id BIGINT     NULL COMMENT '预报的首选天线',
    start_time          DATETIME    NOT NULL COMMENT 'AOS 可见开始(UTC)',
    end_time            DATETIME    NOT NULL COMMENT 'LOS 可见结束(UTC)',
    priority            INT         NOT NULL DEFAULT 5 COMMENT '该过站任务优先级',
    max_elevation       DOUBLE      NULL COMMENT '最大仰角(度)',
    status              VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE/OCCUPIED',
    created_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_window_time (start_time, end_time),
    KEY idx_window_station (station_id),
    KEY idx_window_satellite (satellite_id),
    CONSTRAINT fk_window_satellite FOREIGN KEY (satellite_id) REFERENCES satellite (id),
    CONSTRAINT fk_window_station FOREIGN KEY (station_id) REFERENCES ground_station (id),
    CONSTRAINT fk_window_antenna FOREIGN KEY (preferred_antenna_id) REFERENCES antenna (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='可见窗口(轨道预报)';

CREATE TABLE IF NOT EXISTS maintenance_block (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    station_id   BIGINT      NOT NULL,
    antenna_id   BIGINT      NULL COMMENT '为空表示全站封锁',
    start_time   DATETIME    NOT NULL COMMENT '封锁开始(UTC)',
    end_time     DATETIME    NOT NULL COMMENT '封锁结束(UTC)',
    reason       VARCHAR(255) NULL,
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_maint_time (start_time, end_time),
    CONSTRAINT fk_maint_station FOREIGN KEY (station_id) REFERENCES ground_station (id),
    CONSTRAINT fk_maint_antenna FOREIGN KEY (antenna_id) REFERENCES antenna (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='维护封锁区间';

CREATE TABLE IF NOT EXISTS schedule_version (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    version_no    INT         NULL COMMENT '发布后分配的版本号',
    status        VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/SUPERSEDED',
    base_version_id BIGINT    NULL COMMENT '修订所基于的已发布版本',
    label         VARCHAR(128) NULL,
    range_start   DATETIME    NULL COMMENT '排程范围起(UTC)',
    range_end     DATETIME    NULL COMMENT '排程范围止(UTC)',
    snapshot      LONGTEXT    NULL COMMENT '发布时刻任务全量快照(JSON)',
    task_count    INT         NOT NULL DEFAULT 0,
    created_by    VARCHAR(64) NOT NULL DEFAULT 'scheduler',
    published_at  DATETIME    NULL,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_version_no (version_no),
    KEY idx_version_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='排程版本';

CREATE TABLE IF NOT EXISTS pass_task (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    window_id      BIGINT      NOT NULL,
    satellite_id   BIGINT      NOT NULL,
    station_id     BIGINT      NOT NULL,
    antenna_id     BIGINT      NOT NULL,
    version_id     BIGINT      NOT NULL COMMENT '当前所属排程版本',
    start_time     DATETIME    NOT NULL COMMENT '任务开始(UTC)',
    end_time       DATETIME    NOT NULL COMMENT '任务结束(UTC)',
    priority       INT         NOT NULL DEFAULT 5,
    status         VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/CANCELLED',
    remark         VARCHAR(255) NULL,
    version        INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_task_station_time (station_id, start_time, end_time),
    KEY idx_task_version (version_id),
    KEY idx_task_window (window_id),
    CONSTRAINT fk_task_window FOREIGN KEY (window_id) REFERENCES visibility_window (id),
    CONSTRAINT fk_task_satellite FOREIGN KEY (satellite_id) REFERENCES satellite (id),
    CONSTRAINT fk_task_station FOREIGN KEY (station_id) REFERENCES ground_station (id),
    CONSTRAINT fk_task_antenna FOREIGN KEY (antenna_id) REFERENCES antenna (id),
    CONSTRAINT fk_task_version FOREIGN KEY (version_id) REFERENCES schedule_version (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='过站任务(排程结果)';

CREATE TABLE IF NOT EXISTS schedule_audit (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    version_id   BIGINT      NOT NULL,
    task_id      BIGINT      NULL,
    action       VARCHAR(16) NOT NULL COMMENT 'GENERATE/ADD/ADJUST/CANCEL/REMOVE/PUBLISH',
    before_json  LONGTEXT    NULL COMMENT '调整前对象',
    after_json   LONGTEXT    NULL COMMENT '调整后对象',
    detail       VARCHAR(255) NULL,
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_audit_version (version_id),
    CONSTRAINT fk_audit_version FOREIGN KEY (version_id) REFERENCES schedule_version (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='排程调整审计(前后版本)';
