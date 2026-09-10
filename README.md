# 卫星地面站过站窗口排程系统

根据卫星轨道可见窗口（AOS/LOS）、地面站天线能力、维护封锁区间与任务优先级生成过站排程。
支持草稿调整、冲突检测（409 返回冲突对象与时间段）、任务取消释放窗口、乐观锁并发控制、
版本快照与修订发布，并提供窗口甘特图、冲突定位、版本对比与发布确认界面。

- **后端**：Java 17 · Spring Boot 3.3 · MyBatis-Plus 3.5 · MySQL 8 · Redis 7
- **前端**：Vue 3 · TypeScript · Vite 5 · Element Plus · Pinia · Axios
- **部署**：Docker Compose 一键启动（mysql / redis / backend / frontend）

---

## 一、快速开始

```bash
docker compose up --build
```

启动完成后：

| 服务 | 地址 | 说明 |
| --- | --- | --- |
| 前端 | http://localhost | Nginx 托管 SPA 并反代 `/api` |
| 后端 API | http://localhost:8080/api | Swagger 风格接口见下文 |
| MySQL | localhost:3306 | 库名 `gs_schedule`，用户 `gs/gs123456`，root `root123` |
| Redis | localhost:6379 | 发布防重锁 |

首次启动时 MySQL 自动执行 `db/schema.sql`（建表）与 `db/seed.sql`（种子数据）。
> 种子时间相对“容器初始化当天 UTC”动态生成，因此首次启动当天演示效果最完整；
> 切换历史日期可通过页面右上角日期选择器。

健康检查通过后后端才启动，无需手工干预。

### 本地开发（可选）

```bash
# 后端（需要本地 JDK17/Maven/MySQL/Redis，或仅启动 compose 中的 mysql、redis）
cd backend && mvn spring-boot:run

# 前端（vite dev server 已把 /api 代理到 localhost:8080）
cd frontend && npm install && npm run dev
```

---

## 二、五分钟演示流程

1. 打开 http://localhost ，进入 **排程工作台**，时间范围默认“当天 00:00–23:59 (UTC)”。
2. 点击 **生成排程草稿**：
   - 入选 5 个任务、落选 4 个窗口（被高优先级挤掉 / 命中维护封锁 / 同站重叠）。
   - “落选与冲突”页签中每个落选窗口都带 **冲突对象 + 冲突时间段**，点击标签可跳转定位。
3. 在 **冲突定位视图** 查看甘特图：红色脉冲条即冲突时间段，维护封锁为橙色斜纹，
   跨午夜条带 🌙 标记（北京 23:40–次日 00:05 等）。
4. 在任务清单对任意任务点 **调整**（改时间/天线/优先级，携带乐观锁版本号）：
   - 改成与其它任务重叠的时段提交 → 收到 **HTTP 409**，弹出冲突对象与时段，调整不落库。
5. 点 **取消释放**：任务置为 CANCELLED，可见窗口状态立即恢复 `AVAILABLE`，可重新安排。
6. 点 **去发布** 进入发布确认页 → 勾选确认 → **确认发布**（得到 v1）。
   - 再次点发布/对该版本做任何修改 → 409 拒绝，提示只能通过新版本修订。
7. 回到 **版本管理与对比**，对 v1 点 **创建修订版**，在新草稿中调整后发布得到 v2，
   然后选择 v1 / v2 **开始对比**：列出新增、删除、逐字段修改（旧值 → 新值）。
8. 顶部筛选支持按 **日期 / 地面站 / 卫星** 过滤；可切换 **UTC / 站址本地时区** 显示。

### 种子数据内置的典型场景

| 场景 | 数据 | 预期 |
| --- | --- | --- |
| 同站时间重叠，优先级决胜 | 北京 01:00–01:12（优先级 8）vs 01:06–01:18（优先级 6） | 高优先级入选，低优先级返回 `STATION_OVERLAP` 冲突时段 |
| 维护封锁 + 频段能力受限 | 北京 ANT-A 02:50–03:20 检修；窗口 03:00–03:15 为 S 频段，ANT-B 仅 X | 无可用天线，落选并返回 `MAINTENANCE_BLOCK` |
| 跨午夜窗口 | 北京 23:40–次日 00:05 与 23:50–次日 00:15 | 跨午夜正常参与区间运算，两个窗口同站重叠，优先级 10 入选、另一个落选 |
| 单天线站竞争 | 三亚 12:00 与 12:10 两窗口，仅一副天线 | 优先级 8 的入选，优先级 5 的落选 |
| 不重叠双窗口 | 喀什 05:00–05:10 与 05:15–05:25 | 两窗口均入选（时间不相交，各走一副天线） |
| 全站封锁 | 喀什次日 06:00–08:00 全站 | 该时段任意天线提交均冲突 |

---

## 三、核心业务规则

### 1. 时间、时区与跨午夜

- 数据库 `DATETIME` 一律存 **UTC**（MySQL 容器 `TZ=UTC`、JDBC `serverTimezone=UTC`）；
  接口出入参为 ISO-8601：`2026-09-10T23:40:00Z`，也接受 `+08:00` 偏移输入（服务端换算 UTC）。
- 地面站的 `timezone`（IANA，如 `Asia/Shanghai`）**只用于前端展示**，不影响存储与判定。
- **跨午夜不是特殊情况**：窗口/任务统一用半开区间 `[startTime, endTime)` 做相交运算，
  `endTime` 落在次日即可，无需拆分。甘特图按 UTC 24h 轴绘制，跨午夜条带 🌉 标记，
  在相邻两天的视图中都会出现（超出部分裁剪显示）。

相交判定：`start1 < end2 && start2 < end1`。

### 2. 排程生成（贪心）

候选可见窗口（与排程范围相交、`AVAILABLE`、满足卫星/地面站筛选）按
**优先级降序、AOS 升序** 逐个尝试：

1. 选出该站 `ENABLED` 且频段覆盖卫星频段（`S/X` 支持 `S`、`X`、`S/X`）的天线，首选天线优先；
2. 做硬约束校验（见下），全部通过则落任务，否则尝试下一副天线；
3. 所有天线都失败 → 记入落选列表，返回每一次尝试的冲突对象与时间段。

重复点“追加自动排程”不会重复安排已入选窗口。

### 3. 硬约束（冲突检测）

- **同一地面站任务时间不得重叠**（即使占用不同天线也禁止）；
- 任务时段不得与该天线的维护封锁或全站封锁相交；
- 天线必须存在、属于该站、`ENABLED` 且频段能力覆盖卫星频段；
- 任务时间必须是可见窗口的非空子集。

冲突提交（手工添加/调整）**整体拒绝、不写入任何数据**，返回：

```http
HTTP/1.1 409 Conflict
{
  "code": 4090,
  "message": "任务提交存在冲突，已拒绝（未写入任何数据）",
  "data": {
    "conflicts": [
      {
        "type": "STATION_OVERLAP",
        "reason": "与地面站已有任务[id=12]时间重叠，同一地面站禁止重叠排程",
        "overlapStart": "2026-09-10T01:06:00Z",
        "overlapEnd": "2026-09-10T01:12:00Z",
        "overlapSeconds": 360,
        "stationId": 100,
        "antennaId": 101,
        "existingTask": { "id": 12, "windowId": 1001, "...": "..." }
      },
      {
        "type": "MAINTENANCE_BLOCK",
        "reason": "命中维护封锁：伺服系统例行检修",
        "overlapStart": "2026-09-10T03:00:00Z",
        "overlapEnd": "2026-09-10T03:15:00Z",
        "maintenanceBlockId": 1
      }
    ]
  }
}
```

冲突类型：`STATION_OVERLAP`（同站重叠）、`MAINTENANCE_BLOCK`（维护封锁）、
`ANTENNA_CAPABILITY`（天线不存在/停用/频段不支持）、`WINDOW_ALREADY_SCHEDULED`（窗口已有任务）、
`INVALID_TIME_RANGE`。

前端拦截 409 后不弹通用错误，而是在“冲突定位视图”以红色脉冲条渲染 `overlapStart~overlapEnd`，
支持逐站闪烁定位。

### 4. 取消释放窗口

任务取消（CANCELLED，留痕保留）后，系统重新统计该窗口在**有效版本（草稿/当前发布，不含已取代版本）**
中的活动任务数：为 0 则窗口恢复 `AVAILABLE`，可被再次排程；删除草稿任务同样释放。

### 5. 乐观锁

`pass_task.version` 由 MyBatis-Plus `OptimisticLockerInnerInterceptor` 管理：

- 调整接口必须回传当前 `version`；
- SQL 形如 `UPDATE pass_task SET ..., version=version+1 WHERE id=? AND version=?`；
- 影响行数为 0（他人已改过）→ **HTTP 409 / code=4091**，返回双方版本号，前端提示刷新。

### 6. 发布、重复发布与修订

- 发布是版本状态机：`DRAFT → PUBLISHED`，旧发布版本自动变 `SUPERSEDED`；
- 发布时写入任务全量 JSON **快照**（版本留档 + 对比依据），任务状态转 PUBLISHED；
- **防重复发布三道防线**：
  1. Redis `SET key NX EX 30` 短锁（`gs:publish:v{id}`）拦截重复点击 → 409 / 4092；
  2. 状态守卫：非 DRAFT 不允许发布；
  3. 条件更新 `WHERE status='DRAFT'` + `version_no` 唯一索引兜底数据库并发；
- **已发布版本不可变**：任何调整/取消/删除都返回 409 / 4093，只能
  `POST /api/versions/{id}/revise` 深拷贝出一个新的 DRAFT（`base_version_id` 指回基线），
  在新版本上修订后发布（v1 → v2 …）。

### 7. 调整留痕与版本对比

- 每次 GENERATE / ADD / ADJUST / CANCEL / REMOVE / PUBLISH / REVISE 写 `schedule_audit`，
  保存 **调整前 JSON / 调整后 JSON**，前端“调整留痕”时间线可并排查看；
- 版本对比优先读取发布快照（已取代版本也可对比），以 `windowId` 为主键输出
  `added / removed / changed / unchanged`，修改项逐字段给出 `before → after`。

---

## 四、API 一览

统一响应：`{ "code": 0, "message": "ok", "data": ... }`，业务错误 `code != 0`，
冲突类错误 HTTP 409。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/satellites` `/api/stations` `/api/antennas?stationId=` | 资源查询 |
| POST | `/api/satellites` `/api/stations` `/api/antennas` | 新增/更新（带 id 即更新） |
| GET | `/api/windows?date=&stationId=&satelliteId=` | 某日可见窗口（相交即返回，含跨午夜） |
| POST/DELETE | `/api/windows` `/api/windows/{id}` | 窗口维护 |
| GET/POST/DELETE | `/api/maintenance[...]` | 维护封锁区间维护 |
| GET | `/api/gantt?date=&stationId=&satelliteId` | 甘特图聚合（窗口/封锁/最新草稿/最新发布任务） |
| POST | `/api/schedule/generate` | 生成/追加排程草稿，返回入选 + 落选冲突明细 |
| GET | `/api/versions` `/api/versions/{id}?includeCancelled=` | 版本列表/详情（任务+审计） |
| POST | `/api/versions/{id}/publish` | 发布（body：`{"operator":"zhang"}`） |
| POST | `/api/versions/{id}/revise` | 基于已发布版本创建修订草稿 |
| GET | `/api/versions/compare?left=&right=` | 版本对比 |
| POST | `/api/tasks` | 手工提交任务（冲突 409 返回冲突对象） |
| POST | `/api/tasks/{id}/adjust` | 调整（body 必带 `version`，乐观锁） |
| POST | `/api/tasks/{id}/cancel` | 取消并释放窗口（body：`{"reason":"..."}`） |
| DELETE | `/api/tasks/{id}` | 从草稿移除并释放窗口 |

快速验证：

```bash
curl http://localhost:8080/api/stations
curl -X POST http://localhost:8080/api/schedule/generate \
  -H 'Content-Type: application/json' \
  -d '{"rangeStart":"2026-09-10T00:00:00Z","rangeEnd":"2026-09-10T23:59:00Z"}'
```

---

## 五、数据库模型

```
satellite          卫星（编号/频段/默认优先级）
ground_station     地面站（IANA 时区仅用于展示）
antenna            天线（归属站、频段能力、ENABLED/MAINTENANCE 状态）
visibility_window  可见窗口 AOS/LOS（UTC）、首选天线、优先级、AVAILABLE/OCCUPIED
maintenance_block  维护封锁（antenna_id 为空=全站封锁）
schedule_version   排程版本（DRAFT/PUBLISHED/SUPERSEDED、版本号、范围、JSON 快照）
pass_task          过站任务（版本、窗口、站、天线、起止、优先级、状态、version 乐观锁）
schedule_audit     调整审计（动作、before_json、after_json）
```

关键索引：窗口/任务/封锁表均有 `(start_time, end_time)` 与站点索引；
重叠查询使用单条范围条件 `start_time < :end AND end_time > :start`，可走索引。

---

## 六、目录结构

```
.
├── docker-compose.yml          # mysql + redis + backend + frontend
├── db/
│   ├── schema.sql              # 8 张表（首次启动自动执行）
│   └── seed.sql                # 动态日期种子：重叠/封锁/跨午夜场景
├── backend/
│   ├── Dockerfile              # Maven 多阶段构建 → JRE17 运行
│   ├── pom.xml
│   └── src/main/java/com/gs/schedule/
│       ├── common/             # R 统一响应、业务/乐观锁异常、全局异常处理
│       ├── config/             # MyBatis-Plus 乐观锁、Jackson UTC、CORS
│       ├── entity/ mapper/ dto/
│       ├── service/
│       │   ├── ScheduleConflictDetector.java  # 重叠/封锁/能力检测
│       │   ├── ScheduleService.java           # 生成/调整/取消/发布/修订/对比
│       │   ├── ResourceService.java  ViewAssembler.java  AuditService.java
│       └── controller/         # ResourceController、ScheduleController
└── frontend/
    ├── Dockerfile nginx.conf   # 构建静态资源 + Nginx 反代 /api
    └── src/
        ├── api/                # axios（409 冲突对象透传）、接口封装
        ├── stores/             # Pinia 资源与全局筛选
        ├── components/GanttChart.vue          # 24h 泳道甘特/封锁斜纹/冲突脉冲
        └── views/              # 甘特图、工作台、版本对比、发布确认、资源管理
```

## 七、生产化备注

- 排程生成在单库事务内完成，规模更大时可将冲突检测收敛为站点维度的范围 SQL 批量预载；
- 当前发布锁为 Redis 30s 短锁，仅用于防重复提交；状态条件更新本身已保证幂等正确性；
- 前端时区切换只影响展示，提交始终为 UTC ISO 字符串。
