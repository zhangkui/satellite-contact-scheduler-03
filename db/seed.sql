-- =====================================================================
-- 种子数据
-- 时间均基于容器首次初始化的“当天 UTC”动态生成，因此任何日期启动
-- 都能看到：跨午夜窗口、同站重叠、维护封锁、不同优先级等典型场景。
-- =====================================================================
SET NAMES utf8mb4;

SET @day = CURDATE();

-- ---------- 卫星 ----------
INSERT INTO satellite (id, code, name, band, orbit_type, priority, enabled) VALUES
  (1, 'SAT-01', '东方红一号模拟星', 'S',   'LEO', 8, 1),
  (2, 'SAT-02', '海洋观测星',       'X',   'LEO', 6, 1),
  (3, 'SAT-03', '高分普查星',       'X',   'LEO', 10, 1),
  (4, 'SAT-04', '通信试验星',       'S/X', 'LEO', 5, 1);

-- ---------- 地面站（timezone 仅用于前端本地展示） ----------
INSERT INTO ground_station (id, code, name, region, timezone, longitude, latitude, enabled) VALUES
  (100, 'GS-BJ', '北京地面站', '中国北京', 'Asia/Shanghai', 116.40, 39.90, 1),
  (200, 'GS-KS', '喀什地面站', '新疆喀什', 'Asia/Urumqi',    75.99, 39.46, 1),
  (300, 'GS-SY', '三亚地面站', '海南三亚', 'Asia/Shanghai', 109.51, 18.25, 1);

-- ---------- 天线 ----------
INSERT INTO antenna (id, station_id, code, name, band, status) VALUES
  (101, 100, 'ANT-A', '北京 A 天线(S/X)', 'S/X', 'ENABLED'),
  (102, 100, 'ANT-B', '北京 B 天线(X)',   'X',   'ENABLED'),
  (201, 200, 'ANT-A', '喀什 A 天线(S/X)', 'S/X', 'ENABLED'),
  (202, 200, 'ANT-B', '喀什 B 天线(S/X)', 'S/X', 'ENABLED'),
  (301, 300, 'ANT-A', '三亚 A 天线(S/X)', 'S/X', 'ENABLED');

-- ---------- 可见窗口 ----------
-- 北京：傍晚重叠一对 + 跨午夜一对 + 一个被维护封锁的窗口
INSERT INTO visibility_window
  (id, satellite_id, station_id, preferred_antenna_id, start_time, end_time, priority, max_elevation, status) VALUES
  (1001, 1, 100, 101, TIMESTAMP(DATE_ADD(@day, INTERVAL 1 HOUR)),  TIMESTAMP(DATE_ADD(@day, INTERVAL '1:12' HOUR_MINUTE)), 8,  42.5, 'AVAILABLE'),
  (1002, 2, 100, 101, TIMESTAMP(DATE_ADD(@day, INTERVAL '1:06' HOUR_MINUTE)), TIMESTAMP(DATE_ADD(@day, INTERVAL '1:18' HOUR_MINUTE)), 6, 35.1, 'AVAILABLE'),
  (1003, 1, 100, 101, TIMESTAMP(DATE_ADD(@day, INTERVAL 3 HOUR)),  TIMESTAMP(DATE_ADD(@day, INTERVAL '3:15' HOUR_MINUTE)),  8,  61.0, 'AVAILABLE'),
  -- 跨午夜窗口 1：23:40 - 次日 00:05
  (1004, 3, 100, 101, TIMESTAMP(DATE_ADD(@day, INTERVAL 23 HOUR)), TIMESTAMP(DATE_ADD(DATE_ADD(@day, INTERVAL 1 DAY), INTERVAL 5 MINUTE)), 10, 78.3, 'AVAILABLE'),
  -- 跨午夜窗口 2：23:50 - 次日 00:15，与 1004 重叠，可由 ANT-B(X) 承接
  (1005, 2, 100, 102, TIMESTAMP(DATE_ADD(@day, INTERVAL '23:50' HOUR_MINUTE)), TIMESTAMP(DATE_ADD(DATE_ADD(@day, INTERVAL 1 DAY), INTERVAL 15 MINUTE)), 6, 29.7, 'AVAILABLE');

-- 喀什：两个不重叠窗口，分别走两副天线，均可入选
INSERT INTO visibility_window
  (id, satellite_id, station_id, preferred_antenna_id, start_time, end_time, priority, max_elevation, status) VALUES
  (2001, 2, 200, 201, TIMESTAMP(DATE_ADD(@day, INTERVAL 5 HOUR)),  TIMESTAMP(DATE_ADD(@day, INTERVAL '5:10' HOUR_MINUTE)), 6, 55.2, 'AVAILABLE'),
  (2002, 3, 200, 202, TIMESTAMP(DATE_ADD(@day, INTERVAL '5:15' HOUR_MINUTE)), TIMESTAMP(DATE_ADD(@day, INTERVAL '5:25' HOUR_MINUTE)), 10, 48.9, 'AVAILABLE');

-- 三亚：单天线，两个窗口重叠 -> 高优先级入选，低优先级进入冲突/落选
INSERT INTO visibility_window
  (id, satellite_id, station_id, preferred_antenna_id, start_time, end_time, priority, max_elevation, status) VALUES
  (3001, 4, 300, 301, TIMESTAMP(DATE_ADD(@day, INTERVAL 12 HOUR)), TIMESTAMP(DATE_ADD(@day, INTERVAL '12:12' HOUR_MINUTE)), 5, 33.8, 'AVAILABLE'),
  (3002, 1, 300, 301, TIMESTAMP(DATE_ADD(@day, INTERVAL '12:10' HOUR_MINUTE)), TIMESTAMP(DATE_ADD(@day, INTERVAL '12:22' HOUR_MINUTE)), 8, 40.2, 'AVAILABLE');

-- ---------- 维护封锁 ----------
-- 北京 ANT-A 02:50-03:20 封锁：窗口 1003 只支持 S 频段且首选该天线，将无法安排
INSERT INTO maintenance_block (id, station_id, antenna_id, start_time, end_time, reason) VALUES
  (1, 100, 101,
   TIMESTAMP(DATE_ADD(@day, INTERVAL '2:50' HOUR_MINUTE)),
   TIMESTAMP(DATE_ADD(@day, INTERVAL '3:20' HOUR_MINUTE)),
   '伺服系统例行检修'),
  (2, 200, NULL,
   TIMESTAMP(DATE_ADD(DATE_ADD(@day, INTERVAL 1 DAY), INTERVAL 6 HOUR)),
   TIMESTAMP(DATE_ADD(DATE_ADD(@day, INTERVAL 1 DAY), INTERVAL 8 HOUR)),
   '全站供电改造');
