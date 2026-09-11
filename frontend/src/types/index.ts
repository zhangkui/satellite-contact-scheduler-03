// 领域类型定义。后端时间均为 UTC ISO 字符串（以 Z 结尾）。
export interface Satellite {
  id: number
  code: string
  name: string
  band: string
  orbitType: string
  priority: number
  enabled: boolean
}

export interface GroundStation {
  id: number
  code: string
  name: string
  region?: string
  timezone: string
  longitude?: number
  latitude?: number
  enabled: boolean
}

export interface Antenna {
  id: number
  stationId: number
  code: string
  name: string
  band: string
  status: 'ENABLED' | 'DISABLED' | 'MAINTENANCE'
}

export interface VisibilityWindow {
  id: number
  satelliteId: number
  satelliteCode?: string
  satelliteName?: string
  satelliteBand?: string
  stationId: number
  stationCode?: string
  stationName?: string
  preferredAntennaId?: number
  preferredAntennaCode?: string
  startTime: string
  endTime: string
  priority: number
  maxElevation?: number
  status: 'AVAILABLE' | 'OCCUPIED'
  crossMidnight?: boolean
}

export interface MaintenanceBlock {
  id: number
  stationId: number
  antennaId?: number | null
  startTime: string
  endTime: string
  reason?: string
}

export type TaskStatus = 'DRAFT' | 'PUBLISHED' | 'CANCELLED'

export interface PassTask {
  id: number
  windowId: number
  versionId: number
  satelliteId: number
  satelliteCode?: string
  satelliteName?: string
  stationId: number
  stationCode?: string
  stationName?: string
  antennaId: number
  antennaCode?: string
  antennaName?: string
  startTime: string
  endTime: string
  priority: number
  status: TaskStatus
  remark?: string
  version: number
  crossMidnight?: boolean
  updatedAt?: string
}

export type VersionStatus = 'DRAFT' | 'PUBLISHED' | 'SUPERSEDED'

export interface ScheduleVersion {
  id: number
  versionNo: number | null
  status: VersionStatus
  baseVersionId?: number | null
  label?: string
  rangeStart?: string
  rangeEnd?: string
  snapshot?: string
  taskCount: number
  createdBy: string
  publishedAt?: string
  createdAt: string
}

export interface ConflictInfo {
  type: 'STATION_OVERLAP' | 'MAINTENANCE_BLOCK' | 'ANTENNA_CAPABILITY' |
        'WINDOW_ALREADY_SCHEDULED' | 'RESOURCE_DISABLED' | 'INVALID_TIME_RANGE' | string
  reason: string
  overlapStart?: string
  overlapEnd?: string
  overlapSeconds?: number
  stationId?: number
  antennaId?: number
  existingTask?: PassTask
  maintenanceBlockId?: number
  windowId?: number
}

export interface ScheduleAudit {
  id: number
  versionId: number
  taskId?: number
  action: string
  beforeJson?: string
  afterJson?: string
  detail?: string
  createdAt: string
}

export interface GenerateResult {
  versionId: number
  versionStatus: string
  rangeStart: string
  rangeEnd: string
  scheduledCount: number
  rejectedCount: number
  scheduled: PassTask[]
  rejected: Array<{
    windowId: number
    satelliteId: number
    stationId: number
    startTime: string
    endTime: string
    priority: number
    conflicts: ConflictInfo[]
  }>
}

export interface GanttData {
  rangeStart: string
  rangeEnd: string
  stations: GroundStation[]
  antennas: Antenna[]
  windows: VisibilityWindow[]
  maintenance: MaintenanceBlock[]
  publishedVersion: ScheduleVersion | null
  draftVersion: ScheduleVersion | null
  publishedTasks: PassTask[]
  draftTasks: PassTask[]
}

// ---------------- 预检 ----------------

export interface PrecheckSummary {
  totalWindows: number
  schedulableCount: number
  rejectedCount: number
  crossMidnightWindows: number
  rangeCrossesMidnight: boolean
  /** 潜在冲突类型 → 受影响窗口数。 */
  conflictTypeCounts: Record<string, number>
  /** 受影响资源 id 列表。 */
  affectedResources: {
    stationIds: number[]
    antennaIds: number[]
    satelliteIds: number[]
    maintenanceBlockIds: number[]
    blockingTaskIds: number[]
  }
  warnings: string[]
}

export interface PrecheckWindowDetail {
  windowId: number
  satelliteId: number
  stationId: number
  startTime: string
  endTime: string
  priority: number
  crossMidnight: boolean
  windowStatus: string
  verdict: 'SCHEDULABLE' | 'REJECTED'
  antennaId?: number
  conflicts: ConflictInfo[]
}

export interface PrecheckReport {
  id: number
  requestHash: string
  rangeStart: string
  rangeEnd: string
  stationIds: number[]
  satelliteIds: number[]
  /** 分析所依据的数据版本指纹。 */
  dataVersion?: string
  status: 'RUNNING' | 'COMPLETED' | 'FAILED'
  totalWindows: number
  schedulableCount: number
  rejectedCount: number
  summary?: PrecheckSummary
  /** 逐窗口明细（仅详情接口返回）。 */
  windows?: PrecheckWindowDetail[]
  errorMessage?: string
  operator: string
  createdAt: string
  updatedAt?: string
  /** true 表示命中同参数既有报告，未重复分析。 */
  cached?: boolean
}
