<template>
  <div class="gantt-wrapper">
    <div class="gantt-header-row">
      <div class="lane-label corner">
        <template v-if="tzMode === 'UTC'">地面站 \\ UTC 时刻</template>
        <template v-else>地面站 \\ 轴=UTC，条上为站址本地时</template>
      </div>
      <div class="timeline">
        <div v-for="h in hours" :key="h" class="tick" :style="{ left: (h / 24 * 100) + '%' }">
          <span class="tick-label">{{ String(h).padStart(2, '0') }}</span>
        </div>
        <div class="tick" :style="{ left: '100%' }">
          <span class="tick-label">24<span class="next-day">+1</span></span>
        </div>
      </div>
    </div>

    <div v-for="station in lanes" :key="station.id" class="lane-row"
         :class="{ 'lane-flash': flashLaneId === station.id }">
      <div class="lane-label">
        <div class="lane-name">{{ station.name }}</div>
        <div class="lane-meta">{{ station.code }} · {{ effectiveTz(station.id) }}</div>
      </div>
      <div class="timeline lane-track">
        <!-- 小时网格 -->
        <div v-for="h in 25" :key="h" class="grid-line"
             :style="{ left: (((h - 1) / 24) * 100) + '%' }"></div>
        <!-- 夜间底纹（UTC 18:00-24:00 + 00:00-03:00，仅 UTC 轴模式展示） -->
        <template v-if="tzMode === 'UTC'">
          <div class="night-band" style="left: 0; width: 12.5%"></div>
          <div class="night-band" :style="nightStyle"></div>
        </template>

        <!-- 可见窗口（底层描边） -->
        <div v-for="w in windowsByLane(station.id)" :key="'w' + w.id"
             class="bar window-bar" :style="barStyle(w.startTime, w.endTime)">
          <span class="bar-text">{{ barText(w, station.id) }}</span>
        </div>

        <!-- 维护封锁（斜纹覆盖） -->
        <div v-for="m in maintenanceByLane(station.id)" :key="'m' + m.id"
             class="bar maint-bar" :style="barStyle(m.startTime, m.endTime)"
             :title="`维护封锁 ${fmtRange(m.startTime, m.endTime, station.id)} ${m.reason || ''}`">
          <span class="bar-text">🚧 {{ m.reason || '维护封锁' }}</span>
        </div>

        <!-- 任务（彩色实体条） -->
        <div v-for="t in tasksByLane(station.id)" :key="'t' + t.id"
             class="bar task-bar" :class="{ cancelled: t.status === 'CANCELLED' }"
             :style="{ ...barStyle(t.startTime, t.endTime), background: taskColor(t) }"
             @click="$emit('taskClick', t)">
          <span class="bar-text">{{ t.satelliteCode }} · {{ fmtTime(t.startTime, effectiveTz(station.id)) }}
            <el-icon v-if="isCrossMidnight(t)" class="cross-icon"><MoonNight /></el-icon>
          </span>
        </div>

        <!-- 冲突区间高亮定位 -->
        <div v-for="(c, i) in conflictsByLane(station.id)" :key="'c' + i"
             class="bar conflict-bar" :style="barStyle(c.overlapStart!, c.overlapEnd!)"
             :title="c.reason">
          <span class="bar-text">⚠ {{ conflictTypeLabel(c.type) }}</span>
        </div>
      </div>
    </div>

    <div class="legend">
      <span class="legend-item"><i class="sw sw-window"></i>可见窗口</span>
      <span class="legend-item"><i class="sw sw-maint"></i>维护封锁</span>
      <span class="legend-item"><i class="sw sw-conflict"></i>冲突重叠段</span>
      <span class="legend-item"><i class="sw sw-cross"></i>🌙 跨午夜</span>
      <span v-for="(c, i) in satLegend" :key="i" class="legend-item">
        <i class="sw" :style="{ background: c.color }"></i>{{ c.name }}
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { GroundStation, MaintenanceBlock, PassTask, VisibilityWindow, ConflictInfo } from '@/types'
import { fmtTime, parseTime } from '@/utils/time'
import { useResourceStore } from '@/stores/resource'

const props = defineProps<{
  rangeStart: string
  rangeEnd: string
  stations: GroundStation[]
  windows: VisibilityWindow[]
  tasks: PassTask[]
  maintenance: MaintenanceBlock[]
  conflicts?: Array<ConflictInfo & { stationId?: number }>
  tzMode?: 'UTC' | 'LOCAL'
  flashLaneId?: number
}>()

defineEmits<{ (e: 'taskClick', task: PassTask): void }>()

const store = useResourceStore()
const hours = Array.from({ length: 12 }, (_, i) => i * 2)
const tzMode = computed(() => props.tzMode ?? 'UTC')

const lanes = computed(() => {
  if (props.stations.length) return props.stations
  const ids = new Set<number>()
  props.windows.forEach(w => ids.add(w.stationId))
  props.tasks.forEach(t => ids.add(t.stationId))
  return store.stations.filter(s => ids.has(s.id))
})

function effectiveTz(stationId: number) {
  return store.effectiveTz(stationId)
}

const RANGE_MS = computed(() => parseTime(props.rangeEnd).getTime() - parseTime(props.rangeStart).getTime())

function pct(t: string): number {
  const ms = parseTime(t).getTime() - parseTime(props.rangeStart).getTime()
  return (ms / RANGE_MS.value) * 100
}

function barStyle(start: string, end: string) {
  const left = Math.max(0, pct(start))
  const rightCut = Math.min(100, pct(end))
  return {
    left: left + '%',
    width: Math.max(0.4, rightCut - left) + '%'
  }
}

const nightStyle = computed(() => ({
  left: '75%', width: '25%' // UTC 18:00 - 24:00
}))

function windowsByLane(stationId: number) {
  return props.windows.filter(w => w.stationId === stationId && intersectsRange(w.startTime, w.endTime))
}
function tasksByLane(stationId: number) {
  return props.tasks.filter(t => t.stationId === stationId
    && t.status !== 'CANCELLED' && intersectsRange(t.startTime, t.endTime))
}
function maintenanceByLane(stationId: number) {
  return props.maintenance.filter(m => m.stationId === stationId
    && intersectsRange(m.startTime, m.endTime))
}
function conflictsByLane(stationId: number) {
  return (props.conflicts || []).filter(c =>
    (c.stationId === stationId || c.existingTask?.stationId === stationId)
    && (!c.overlapStart || intersectsRange(c.overlapStart, c.overlapEnd!)))
}

/** 与当前时间轴范围是否相交（半开区间），范围外元素不绘制。 */
function intersectsRange(start: string, end: string) {
  const s = parseTime(start).getTime()
  const e = parseTime(end).getTime()
  const rs = parseTime(props.rangeStart).getTime()
  const re = parseTime(props.rangeEnd).getTime()
  return s < re && rs < e
}

function isCrossMidnight(t: PassTask | VisibilityWindow) {
  if (t.crossMidnight !== undefined) return t.crossMidnight
  return parseTime(t.startTime).getUTCDate() !== parseTime(t.endTime).getUTCDate()
}

function barText(w: VisibilityWindow, stationId: number) {
  return `${w.satelliteCode ?? ''} ${fmtTime(w.startTime, effectiveTz(stationId))}–` +
    `${fmtTime(w.endTime, effectiveTz(stationId))}${isCrossMidnight(w) ? ' 🌙' : ''}`
}

function fmtRange(s: string, e: string, stationId: number) {
  return `${fmtTime(s, effectiveTz(stationId))}–${fmtTime(e, effectiveTz(stationId))}`
}

const PALETTE = ['#409eff', '#67c23a', '#e6a23c', '#9b59b6', '#16a085', '#e74c3c']
function taskColor(t: PassTask) {
  const idx = Math.abs((t.satelliteCode || String(t.satelliteId)).split('')
    .reduce((a, ch) => a + ch.charCodeAt(0), 0)) % PALETTE.length
  return PALETTE[idx]
}

const satLegend = computed(() => {
  const map = new Map<string, string>()
  props.tasks.forEach(t => {
    if (t.satelliteCode && !map.has(t.satelliteCode)) map.set(t.satelliteCode, taskColor(t))
  })
  return Array.from(map.entries()).map(([name, color]) => ({ name, color }))
})

function conflictTypeLabel(type: string) {
  return {
    STATION_OVERLAP: '同站重叠',
    MAINTENANCE_BLOCK: '维护封锁',
    ANTENNA_CAPABILITY: '天线能力',
    WINDOW_ALREADY_SCHEDULED: '窗口已占用'
  }[type] ?? type
}
</script>

<style scoped>
.gantt-wrapper {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  overflow-x: auto;
}
.gantt-header-row {
  display: flex;
  height: 34px;
  border-bottom: 1px solid #ebeef5;
  position: sticky;
  top: 0;
  background: #fafafa;
  z-index: 2;
  min-width: 900px;
}
.lane-row {
  display: flex;
  min-width: 900px;
}
.lane-label {
  width: 150px;
  flex: 0 0 150px;
  padding: 4px 10px;
  border-right: 1px solid #ebeef5;
  background: #fafafa;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.corner {
  justify-content: center;
  font-size: 12px;
  color: #909399;
}
.lane-name {
  font-weight: 600;
  font-size: 13px;
  color: #303133;
}
.lane-meta {
  font-size: 11px;
  color: #909399;
}
.lane-row {
  height: 46px;
  border-bottom: 1px solid #f0f0f0;
}
.lane-row:last-child {
  border-bottom: none;
}
.lane-flash {
  animation: flash 1.2s ease-in-out 2;
}
@keyframes flash {
  0%, 100% { background: #fff; }
  50% { background: #fff7e6; }
}
.timeline {
  position: relative;
  flex: 1;
}
.lane-track {
  overflow: hidden;
}
.tick {
  position: absolute;
  top: 0;
  height: 100%;
}
.tick-label {
  position: absolute;
  top: 8px;
  left: 4px;
  font-size: 11px;
  color: #909399;
}
.next-day {
  color: #e6a23c;
  margin-left: 2px;
  font-size: 10px;
}
.grid-line {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 1px;
  background: #f5f5f5;
}
.night-band {
  position: absolute;
  top: 0;
  bottom: 0;
  background: repeating-linear-gradient(90deg, rgba(64,158,255,0.04) 0 20px, rgba(64,158,255,0.08) 20px 40px);
  pointer-events: none;
}
.bar {
  position: absolute;
  top: 8px;
  height: 30px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  padding: 0 6px;
  font-size: 11px;
  white-space: nowrap;
  overflow: hidden;
  box-sizing: border-box;
  cursor: default;
}
.window-bar {
  top: 11px;
  height: 24px;
  border: 1px dashed #b3d8ff;
  background: rgba(227, 243, 255, 0.55);
  color: #409eff;
}
.maint-bar {
  top: 6px;
  height: 34px;
  background: repeating-linear-gradient(45deg, rgba(230,162,60,0.25) 0 6px, rgba(230,162,60,0.45) 6px 12px);
  border: 1px solid #e6a23c;
  color: #8a5a19;
  z-index: 1;
}
.task-bar {
  color: #fff;
  font-weight: 600;
  cursor: pointer;
  z-index: 3;
  box-shadow: 0 1px 3px rgba(0,0,0,0.2);
}
.task-bar.cancelled {
  opacity: 0.35;
  text-decoration: line-through;
}
.conflict-bar {
  top: 3px;
  height: 40px;
  background: rgba(245, 108, 108, 0.25);
  border: 2px solid #f56c6c;
  color: #c45656;
  font-weight: 700;
  z-index: 4;
  animation: pulse 1s ease-in-out infinite alternate;
  pointer-events: none;
}
@keyframes pulse {
  from { box-shadow: 0 0 0 0 rgba(245,108,108,0.5); }
  to { box-shadow: 0 0 0 5px rgba(245,108,108,0.15); }
}
.bar-text {
  overflow: hidden;
  text-overflow: ellipsis;
}
.cross-icon {
  margin-left: 2px;
}
.legend {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  padding: 8px 12px;
  border-top: 1px solid #ebeef5;
  font-size: 12px;
  color: #606266;
}
.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.sw {
  display: inline-block;
  width: 14px;
  height: 10px;
  border-radius: 2px;
}
.sw-window {
  border: 1px dashed #409eff;
  background: rgba(227,243,255,0.8);
}
.sw-maint {
  background: repeating-linear-gradient(45deg, rgba(230,162,60,0.4) 0 3px, rgba(230,162,60,0.7) 3px 6px);
  border: 1px solid #e6a23c;
}
.sw-conflict {
  background: rgba(245,108,108,0.35);
  border: 1px solid #f56c6c;
}
.sw-cross {
  background: transparent;
}
</style>
