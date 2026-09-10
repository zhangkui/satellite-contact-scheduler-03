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
        'WINDOW_ALREADY_SCHEDULED' | 'INVALID_TIME_RANGE' | string
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
