<template>
  <div class="precheck-panel">
    <el-empty v-if="!report" description="尚未运行预检：设置排程范围后点击顶部「预检分析」" />

    <template v-else>
      <!-- 报告头：范围 / 筛选 / 数据版本 / 操作人 -->
      <div class="report-head">
        <div>
          <span class="report-title">预检报告 #{{ report.id }}</span>
          <el-tag size="small" :type="statusTagType" class="tag">{{ statusLabel }}</el-tag>
          <el-tag v-if="report.cached" size="small" type="info" class="tag" data-test="cached-tag">
            命中同参数历史报告（未重复分析）
          </el-tag>
        </div>
        <div class="meta">
          范围(UTC)：{{ fmtFull(report.rangeStart) }} ~ {{ fmtFull(report.rangeEnd) }}
          · 筛选：站 {{ filterLabel(report.stationIds) }} / 星 {{ filterLabel(report.satelliteIds) }}
          · 操作人 {{ report.operator }}
        </div>
        <div class="meta mono" v-if="report.dataVersion">数据版本：{{ report.dataVersion }}</div>
      </div>

      <el-alert v-if="report.status === 'FAILED'" type="error" show-icon :closable="false"
                :title="`预检失败：${report.errorMessage}`" />

      <template v-if="report.status === 'COMPLETED' && summary">
        <!-- 指标摘要 -->
        <div class="metrics" data-test="metrics">
          <div class="metric">
            <div class="metric-value" data-test="m-total">{{ summary.totalWindows }}</div>
            <div class="metric-label">窗口总数</div>
          </div>
          <div class="metric ok">
            <div class="metric-value" data-test="m-schedulable">{{ summary.schedulableCount }}</div>
            <div class="metric-label">可排窗口</div>
          </div>
          <div class="metric bad">
            <div class="metric-value" data-test="m-rejected">{{ summary.rejectedCount }}</div>
            <div class="metric-label">必然落选</div>
          </div>
          <div class="metric">
            <div class="metric-value" data-test="m-cross">{{ summary.crossMidnightWindows }}</div>
            <div class="metric-label">跨午夜窗口</div>
          </div>
        </div>

        <!-- 潜在冲突类型 + 受影响资源 -->
        <div class="summary-row" data-test="conflict-types">
          <span class="summary-label">潜在冲突类型：</span>
          <template v-if="conflictTypes.length">
            <el-tag v-for="t in conflictTypes" :key="t.type" size="small"
                    :type="conflictTagType(t.type)" class="tag" :data-test="`ctype-${t.type}`">
              {{ conflictLabel(t.type) }} × {{ t.count }}
            </el-tag>
          </template>
          <span v-else class="hint">无</span>
        </div>
        <div class="summary-row" data-test="affected-resources">
          <span class="summary-label">受影响资源：</span>
          <span v-if="!affectedTotal" class="hint">无</span>
          <template v-else>
            <el-tag v-if="affected.stationIds.length" size="small" type="warning" effect="plain"
                    class="tag">地面站 × {{ affected.stationIds.length }}</el-tag>
            <el-tag v-if="affected.antennaIds.length" size="small" type="warning" effect="plain"
                    class="tag">天线 × {{ affected.antennaIds.length }}</el-tag>
            <el-tag v-if="affected.satelliteIds.length" size="small" type="warning" effect="plain"
                    class="tag">卫星 × {{ affected.satelliteIds.length }}</el-tag>
            <el-tag v-if="affected.maintenanceBlockIds.length" size="small" type="warning" effect="plain"
                    class="tag">维护封锁 × {{ affected.maintenanceBlockIds.length }}</el-tag>
            <el-tag v-if="affected.blockingTaskIds.length" size="small" type="warning" effect="plain"
                    class="tag">占用任务 × {{ affected.blockingTaskIds.length }}</el-tag>
          </template>
        </div>

        <el-alert v-for="(w, i) in summary.warnings" :key="i" type="warning" show-icon
                  :closable="false" :title="w" class="warning-item" data-test="warning" />

        <!-- 操作：生成草稿 / 跳转视图 -->
        <div class="actions">
          <el-button type="primary" :loading="generating" data-test="btn-generate"
                     @click="$emit('generate', report)">依据预检生成草稿</el-button>
          <el-button data-test="btn-goto-gantt" @click="$emit('goto', 'gantt')">查看甘特图</el-button>
          <el-button data-test="btn-goto-resources" @click="$emit('goto', 'resources')">资源管理</el-button>
          <el-button :disabled="!rejectedWindows.length" data-test="btn-locate"
                     @click="locateAll">冲突定位</el-button>
        </div>

        <!-- 冲突明细 -->
        <div v-if="rejectedWindows.length" class="conflict-section" data-test="conflict-section">
          <div class="section-title">冲突明细（{{ rejectedWindows.length }} 个必然落选窗口）</div>
          <div v-for="w in rejectedWindows" :key="w.windowId" class="conflict-row"
               :data-test="`conflict-row-${w.windowId}`">
            <div class="conflict-main">
              <span class="mono">#{{ w.windowId }}</span>
              <span>{{ store.stationName(w.stationId) }}</span>
              <span class="mono">{{ fmtFull(w.startTime) }} ~ {{ fmtTime(w.endTime) }}</span>
              <el-tag v-if="w.crossMidnight" size="small" type="warning" effect="plain"
                      data-test="cross-midnight">跨午夜🌙</el-tag>
              <el-tag size="small" effect="plain">P{{ w.priority }}</el-tag>
            </div>
            <div class="conflict-tags">
              <span v-for="(c, i) in w.conflicts" :key="i" class="conflict-tag-wrap"
                    :data-test="`conflict-tag-${c.type}`" @click="locateOne(c)">
                <el-tag size="small" :type="conflictTagType(c.type)" class="conflict-tag">
                  {{ conflictLabel(c.type) }}
                  <span v-if="c.overlapStart">：{{ fmtRange(c) }}</span>
                </el-tag>
              </span>
            </div>
            <div class="conflict-reason">{{ w.conflicts.map(c => c.reason).join('；') }}</div>
          </div>
        </div>
        <el-alert v-else type="success" :closable="false" data-test="all-clear"
                  title="范围内窗口均可排，无必然落选" />
      </template>
    </template>

    <!-- 历史报告：按范围/筛选/数据版本持久化，可重复查看 -->
    <div v-if="reports.length" class="history" data-test="history">
      <div class="section-title">历史报告（同一参数重复预检不会生成重复记录）</div>
      <div v-for="r in reports" :key="r.id" class="history-row"
           :class="{ active: report && r.id === report.id }" :data-test="`history-${r.id}`"
           @click="$emit('view', r)">
        <span class="mono">#{{ r.id }}</span>
        <span class="mono">{{ fmtFull(r.rangeStart) }} ~ {{ fmtFull(r.rangeEnd) }}</span>
        <el-tag size="small"
                :type="r.status === 'COMPLETED' ? 'success' : (r.status === 'FAILED' ? 'danger' : 'info')">
          {{ r.status }}
        </el-tag>
        <span class="hint">可排 {{ r.schedulableCount }} / 落选 {{ r.rejectedCount }} / 共 {{ r.totalWindows }}</span>
        <span class="hint">筛选：站 {{ filterLabel(r.stationIds) }} / 星 {{ filterLabel(r.satelliteIds) }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ConflictInfo, PrecheckReport } from '@/types'
import { useResourceStore } from '@/stores/resource'
import { CONFLICT_LABELS, fmtTime, parseTime } from '@/utils/time'

const props = withDefaults(defineProps<{
  report?: PrecheckReport | null
  reports?: PrecheckReport[]
  generating?: boolean
}>(), { report: null, reports: () => [], generating: false })

const emit = defineEmits<{
  (e: 'locate', conflicts: ConflictInfo[]): void
  (e: 'generate', report: PrecheckReport): void
  (e: 'goto', view: 'gantt' | 'resources'): void
  (e: 'view', report: PrecheckReport): void
}>()

const store = useResourceStore()

const summary = computed(() => props.report?.summary)
const rejectedWindows = computed(() =>
  (props.report?.windows ?? []).filter(w => w.verdict === 'REJECTED'))

const conflictTypes = computed(() =>
  Object.entries(summary.value?.conflictTypeCounts ?? {}).map(([type, count]) => ({ type, count })))

const affected = computed(() => summary.value?.affectedResources ?? {
  stationIds: [], antennaIds: [], satelliteIds: [], maintenanceBlockIds: [], blockingTaskIds: []
})
const affectedTotal = computed(() =>
  affected.value.stationIds.length + affected.value.antennaIds.length
  + affected.value.satelliteIds.length + affected.value.maintenanceBlockIds.length
  + affected.value.blockingTaskIds.length)

const statusTagType = computed(() => ({
  COMPLETED: 'success', FAILED: 'danger', RUNNING: 'info'
} as Record<string, string>)[props.report?.status ?? ''] ?? 'info') as any

const statusLabel = computed(() => ({
  COMPLETED: '已完成', FAILED: '失败', RUNNING: '分析中'
} as Record<string, string>)[props.report?.status ?? ''] ?? props.report?.status)

function locateAll() {
  emit('locate', rejectedWindows.value.flatMap(w => w.conflicts))
}

function locateOne(c: ConflictInfo) {
  emit('locate', [c])
}

function conflictLabel(t: string) {
  return CONFLICT_LABELS[t] ?? t
}
function conflictTagType(t: string) {
  return t === 'MAINTENANCE_BLOCK' ? 'warning' : 'danger'
}
function filterLabel(ids?: number[]) {
  return ids && ids.length ? ids.join(',') : '全部'
}
function fmtFull(s?: string) {
  return s ? parseTime(s).toISOString().slice(0, 16).replace('T', ' ') : '-'
}
function fmtRange(c: ConflictInfo) {
  return c.overlapStart && c.overlapEnd ? `${fmtTime(c.overlapStart)}–${fmtTime(c.overlapEnd)}` : ''
}
</script>

<style scoped>
.report-head {
  margin-bottom: 12px;
}
.report-title {
  font-weight: 600;
  font-size: 15px;
}
.tag {
  margin-left: 6px;
}
.meta {
  color: #909399;
  font-size: 12px;
  margin-top: 4px;
}
.metrics {
  display: flex;
  gap: 12px;
  margin: 12px 0;
}
.metric {
  flex: 1;
  background: #f6f8fa;
  border-radius: 6px;
  padding: 12px;
  text-align: center;
}
.metric-value {
  font-size: 26px;
  font-weight: 700;
}
.metric.ok .metric-value { color: #67c23a; }
.metric.bad .metric-value { color: #f56c6c; }
.metric-label {
  color: #909399;
  font-size: 12px;
  margin-top: 2px;
}
.summary-row {
  margin: 6px 0;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
}
.summary-label {
  color: #606266;
  font-size: 13px;
}
.hint {
  color: #909399;
  font-size: 12px;
}
.warning-item {
  margin-top: 8px;
}
.actions {
  margin: 14px 0;
}
.section-title {
  font-weight: 600;
  margin: 10px 0 8px;
}
.conflict-row {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 8px 12px;
  margin-bottom: 8px;
}
.conflict-main {
  display: flex;
  gap: 10px;
  align-items: center;
}
.conflict-tags {
  margin-top: 6px;
}
.conflict-tag-wrap {
  cursor: pointer;
  display: inline-block;
}
.conflict-tag {
  margin: 2px 6px 2px 0;
}
.conflict-reason {
  color: #909399;
  font-size: 12px;
  margin-top: 4px;
}
.history {
  margin-top: 16px;
  border-top: 1px dashed #e4e7ed;
  padding-top: 8px;
}
.history-row {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 6px 8px;
  border-radius: 4px;
  cursor: pointer;
}
.history-row:hover {
  background: #f6f8fa;
}
.history-row.active {
  background: #ecf5ff;
}
.mono {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 12px;
}
</style>
