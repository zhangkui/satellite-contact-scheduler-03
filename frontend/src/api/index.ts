import http from './http'
import type {
  Antenna, GenerateResult, GanttData, GroundStation, MaintenanceBlock,
  PassTask, PrecheckReport, ScheduleAudit, ScheduleVersion, Satellite, VisibilityWindow
} from '@/types'

// ---------------- 资源 ----------------
export const resourceApi = {
  satellites: () => http.get<any, Satellite[]>('/api/satellites'),
  stations: () => http.get<any, GroundStation[]>('/api/stations'),
  antennas: (stationId?: number) =>
    http.get<any, Antenna[]>('/api/antennas', { params: { stationId } }),
  windows: (params: { stationId?: number; satelliteId?: number; date?: string }) =>
    http.get<any, VisibilityWindow[]>('/api/windows', { params }),
  saveWindow: (data: Partial<VisibilityWindow>) => http.post<any, VisibilityWindow>('/api/windows', data),
  deleteWindow: (id: number) => http.delete(`/api/windows/${id}`),
  maintenance: (params: { stationId?: number; date?: string }) =>
    http.get<any, MaintenanceBlock[]>('/api/maintenance', { params }),
  saveMaintenance: (data: Partial<MaintenanceBlock>) =>
    http.post<any, MaintenanceBlock>('/api/maintenance', data),
  deleteMaintenance: (id: number) => http.delete(`/api/maintenance/${id}`),
  gantt: (params: { date?: string; stationId?: number; satelliteId?: number }) =>
    http.get<any, GanttData>('/api/gantt', { params })
}

// ---------------- 排程 ----------------
export interface GeneratePayload {
  versionId?: number
  label?: string
  rangeStart: string
  rangeEnd: string
  stationIds?: number[]
  satelliteIds?: number[]
}

export const scheduleApi = {
  generate: (payload: GeneratePayload) =>
    http.post<any, GenerateResult>('/api/schedule/generate', payload),

  versions: () => http.get<any, ScheduleVersion[]>('/api/versions'),
  versionDetail: (id: number, includeCancelled = false) =>
    http.get<any, { version: ScheduleVersion; tasks: PassTask[]; audits: ScheduleAudit[] }>(
      `/api/versions/${id}`, { params: { includeCancelled } }),
  publish: (id: number, operator = 'scheduler') =>
    http.post<any, any>(`/api/versions/${id}/publish`, { operator }),
  revise: (id: number) => http.post<any, any>(`/api/versions/${id}/revise`),
  compare: (left: number, right: number) =>
    http.get<any, any>('/api/versions/compare', { params: { left, right } }),

  createTask: (data: {
    versionId: number; windowId: number; antennaId?: number
    startTime?: string; endTime?: string; priority?: number; remark?: string
  }) => http.post<any, PassTask>('/api/tasks', data),

  adjustTask: (id: number, data: {
    antennaId?: number; startTime?: string; endTime?: string
    priority?: number; remark?: string; version: number
  }) => http.post<any, PassTask>(`/api/tasks/${id}/adjust`, data),

  cancelTask: (id: number, reason?: string) =>
    http.post<any, PassTask>(`/api/tasks/${id}/cancel`, { reason }),

  removeTask: (id: number) => http.delete<any, void>(`/api/tasks/${id}`)
}

// ---------------- 预检 ----------------
export interface PrecheckPayload {
  rangeStart: string
  rangeEnd: string
  stationIds?: number[]
  satelliteIds?: number[]
  operator?: string
  /** true 时按相同参数重新分析并覆盖既有报告（仍不产生重复记录）。 */
  refresh?: boolean
}

export const precheckApi = {
  run: (payload: PrecheckPayload) => http.post<any, PrecheckReport>('/api/precheck', payload),
  list: () => http.get<any, PrecheckReport[]>('/api/precheck'),
  detail: (id: number) => http.get<any, PrecheckReport>(`/api/precheck/${id}`),
  generate: (id: number, operator = 'scheduler') =>
    http.post<any, { reportId: number; result: GenerateResult }>(
      `/api/precheck/${id}/generate`, { operator })
}
