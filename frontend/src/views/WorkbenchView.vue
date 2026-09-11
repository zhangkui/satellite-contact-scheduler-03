<template>
  <div class="page-container">
    <!-- 顶部操作区 -->
    <el-card class="section-card">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="排程范围(UTC)">
          <el-date-picker v-model="genRange" type="datetimerange" range-separator="至"
                          start-placeholder="AOS 起" end-placeholder="LOS 止"
                          :default-time="defaultTimes" style="width: 380px" />
        </el-form-item>
        <el-form-item label="地面站">
          <el-select v-model="genStations" multiple collapse-tags placeholder="全部"
                     style="width: 190px">
            <el-option v-for="s in store.stations" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="卫星">
          <el-select v-model="genSatellites" multiple collapse-tags placeholder="全部"
                     style="width: 190px">
            <el-option v-for="s in store.satellites" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button :loading="prechecking" data-test="btn-precheck" @click="doPrecheck">
            预检分析
          </el-button>
          <el-button type="primary" :loading="generating" @click="doGenerate">
            {{ draftId ? '追加自动排程' : '生成排程草稿' }}
          </el-button>
          <el-button @click="resetRange">重置为当天</el-button>
        </el-form-item>
      </el-form>
      <el-alert v-if="lastResult" :closable="false" class="gen-summary"
        :type="lastResult.rejectedCount > 0 ? 'warning' : 'success'" show-icon>
        <template #title>
          草稿 #{{ lastResult.versionId }}：入选 {{ lastResult.scheduledCount }} 个，
          落选 {{ lastResult.rejectedCount }} 个（见落选/冲突表）
        </template>
      </el-alert>
    </el-card>

    <el-tabs v-model="tab">
      <!-- ============ 任务清单 ============ -->
      <el-tab-pane label="任务清单" name="tasks">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>
                草稿 #{{ draftId }}
                <el-tag size="small" type="info">{{ detail?.tasks.length ?? 0 }} 个任务</el-tag>
              </span>
              <div>
                <el-button size="small" @click="loadDraft">刷新</el-button>
                <el-button size="small" type="success" @click="openAddDialog">手工添加任务</el-button>
                <el-button size="small" type="primary" :disabled="!draftId"
                           @click="router.push(`/publish/${draftId}`)">
                  去发布
                </el-button>
              </div>
            </div>
          </template>

          <el-table :data="detail?.tasks ?? []" size="small" :row-class-name="rowClass">
            <el-table-column prop="satelliteCode" label="卫星" width="90" />
            <el-table-column prop="stationName" label="地面站" width="100" />
            <el-table-column prop="antennaCode" label="天线" width="70" />
            <el-table-column label="AOS–LOS (UTC)" width="230">
              <template #default="{ row }">
                <span class="mono">{{ fmtFull(row.startTime) }} ~ {{ fmtFull(row.endTime) }}</span>
                <el-icon v-if="row.crossMidnight" color="#e6a23c"><MoonNight /></el-icon>
              </template>
            </el-table-column>
            <el-table-column prop="priority" label="优先级" width="70" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" show-overflow-tooltip />
            <el-table-column label="操作" width="210" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status !== 'CANCELLED'" link type="primary" size="small"
                           @click="openAdjust(row)">调整</el-button>
                <el-button v-if="row.status !== 'CANCELLED'" link type="warning" size="small"
                           @click="onCancel(row)">取消释放</el-button>
                <el-button link type="danger" size="small" @click="onRemove(row)">移除</el-button>
                <span class="mono v-tag">v{{ row.version }}</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- ============ 落选窗口 / 生成冲突 ============ -->
      <el-tab-pane :label="`落选与冲突 (${lastResult?.rejected.length ?? 0})`" name="rejected">
        <el-card>
          <el-empty v-if="!lastResult || lastResult.rejected.length === 0"
                    description="暂无落选窗口；一次成功的自动排程后此处展示被高优先级/封锁/重叠挤掉的窗口" />
          <el-table v-else :data="rejectedRows" size="small">
            <el-table-column label="窗口" width="160">
              <template #default="{ row }">
                #{{ row.windowId }} · {{ satCode(row.satelliteId) }}
              </template>
            </el-table-column>
            <el-table-column label="地面站" width="110">
              <template #default="{ row }">{{ store.stationName(row.stationId) }}</template>
            </el-table-column>
            <el-table-column label="时段(UTC)" width="210">
              <template #default="{ row }">
                <span class="mono">{{ fmtFull(row.startTime) }} ~ {{ fmtFull(row.endTime) }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="priority" label="优先级" width="70" />
            <el-table-column label="冲突对象与时间段">
              <template #default="{ row }">
                <el-tag v-for="(c, i) in row.conflicts" :key="i" size="small"
                        :type="conflictTagType(c.type)" class="conflict-tag"
                        @click="locateConflict(c)">
                  {{ conflictLabel(c.type) }}
                  <span v-if="c.overlapStart">
                    ：{{ fmtTime(c.overlapStart) }}–{{ fmtTime(c.overlapEnd) }}
                  </span>
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button link type="primary" size="small"
                           @click="manualAddRejected(row)">尝试手工安排</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- ============ 预检报告 ============ -->
      <el-tab-pane :label="`预检报告${precheckReport ? ' #' + precheckReport.id : ''}`" name="precheck">
        <el-card v-loading="prechecking">
          <PrecheckPanel
            :report="precheckReport"
            :reports="precheckReports"
            :generating="precheckGenerating"
            @locate="onPrecheckLocate"
            @generate="onPrecheckGenerate"
            @goto="onPrecheckGoto"
            @view="onPrecheckView"
          />
        </el-card>
      </el-tab-pane>

      <!-- ============ 冲突定位甘特图 ============ -->
      <el-tab-pane label="冲突定位视图" name="gantt">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>甘特冲突定位（红色脉冲区域即冲突时间段）</span>
              <el-button size="small" :disabled="!activeConflicts.length"
                         @click="flashNext">闪烁定位下一个冲突</el-button>
            </div>
          </template>
          <GanttChart
            :range-start="ganttRangeStart" :range-end="ganttRangeEnd"
            :stations="store.stations"
            :windows="ganttData?.windows ?? []"
            :tasks="ganttTasks"
            :maintenance="ganttData?.maintenance ?? []"
            :conflicts="activeConflicts"
            :tz-mode="store.displayTz"
            :flash-lane-id="flashLaneId"
          />
        </el-card>
      </el-tab-pane>

      <!-- ============ 调整审计（前后版本） ============ -->
      <el-tab-pane label="调整留痕" name="audit">
        <el-card>
          <el-timeline>
            <el-timeline-item v-for="a in detail?.audits ?? []" :key="a.id"
                              :timestamp="fmtFull(a.createdAt)" placement="top"
                              :type="auditColor(a.action)">
              <el-tag size="small" :type="auditColor(a.action)">{{ actionLabel(a.action) }}</el-tag>
              <span style="margin-left: 8px">{{ a.detail }}</span>
              <el-popover v-if="a.beforeJson || a.afterJson" trigger="click" width="560"
                          placement="bottom-start">
                <template #reference>
                  <el-button link type="primary" size="small" style="margin-left: 8px">查看前后对比</el-button>
                </template>
                <el-row :gutter="10">
                  <el-col :span="12">
                    <div class="diff-title before">调整前</div>
                    <pre class="diff-json">{{ pretty(a.beforeJson) }}</pre>
                  </el-col>
                  <el-col :span="12">
                    <div class="diff-title after">调整后</div>
                    <pre class="diff-json">{{ pretty(a.afterJson) }}</pre>
                  </el-col>
                </el-row>
              </el-popover>
            </el-timeline-item>
          </el-timeline>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 调整对话框（乐观锁 version） -->
    <el-dialog v-model="adjustVisible" title="调整任务" width="560px">
      <el-form label-width="100px">
        <el-form-item label="时间(UTC)">
          <el-date-picker v-model="adjustRange" type="datetimerange" range-separator="至"
                          style="width: 100%" />
        </el-form-item>
        <el-form-item label="天线">
          <el-select v-model="adjustForm.antennaId" style="width: 100%">
            <el-option v-for="a in stationAntennas(adjustForm.stationId)" :key="a.id"
                       :label="`${a.code} ${a.name} (${a.band})`" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="adjustForm.priority" :min="1" :max="10" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="adjustForm.remark" />
        </el-form-item>
        <el-form-item label="乐观锁版本">
          <el-tag size="small">当前 v{{ adjustForm.version }}</el-tag>
          <span class="hint">提交时携带，他人已修改将返回 409 并要求刷新</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitAdjust">保存调整</el-button>
      </template>
    </el-dialog>

    <!-- 手工添加对话框 -->
    <el-dialog v-model="addVisible" title="手工添加任务" width="560px">
      <el-form label-width="100px">
        <el-form-item label="可见窗口">
          <el-select v-model="addForm.windowId" filterable style="width: 100%"
                     placeholder="选择 AVAILABLE 窗口">
            <el-option v-for="w in addableWindows" :key="w.id"
                       :label="`#${w.id} ${w.satelliteCode} @ ${w.stationName} ${fmtFull(w.startTime)}~${fmtTime(w.endTime)}`"
                       :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="天线">
          <el-select v-model="addForm.antennaId" clearable style="width: 100%"
                     placeholder="留空使用首选天线/自动选择">
            <el-option v-for="a in selectedWindowAntennas" :key="a.id"
                       :label="`${a.code} ${a.name} (${a.band})`" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="时间(UTC)">
          <el-date-picker v-model="addRange" type="datetimerange" range-separator="至"
                          style="width: 100%" />
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="addForm.priority" :min="1" :max="10" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="addForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitAdd">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import GanttChart from '@/components/GanttChart.vue'
import PrecheckPanel from '@/components/PrecheckPanel.vue'
import { precheckApi, resourceApi, scheduleApi } from '@/api'
import type { ApiError } from '@/api/http'
import type {
  ConflictInfo, GenerateResult, GanttData, PassTask, PrecheckReport, VisibilityWindow
} from '@/types'
import { useResourceStore } from '@/stores/resource'
import {
  ACTION_LABELS, CONFLICT_LABELS, addDaysUtc, fmtDateTime, fmtTime, parseTime, toUtcIso, utcDate
} from '@/utils/time'

const router = useRouter()
const store = useResourceStore()

// ---------- 生成 ----------
const defaultTimes = [new Date(2000, 0, 1, 0, 0), new Date(2000, 0, 1, 23, 59)]
const genRange = ref<[Date, Date]>(defaultDayRange())
const genStations = ref<number[]>([])
const genSatellites = ref<number[]>([])
const generating = ref(false)
const lastResult = ref<GenerateResult>()

const draftId = computed(() => lastResult.value?.versionId)
const tab = ref('tasks')

function defaultDayRange(): [Date, Date] {
  const d = utcDate()
  return [parseTime(`${d}T00:00:00Z`), parseTime(`${d}T23:59:00Z`)]
}
function resetRange() {
  genRange.value = defaultDayRange()
}

async function doGenerate() {
  if (!genRange.value) {
    ElMessage.warning('请选择排程时间范围')
    return
  }
  generating.value = true
  activeConflicts.value = []
  try {
    lastResult.value = await scheduleApi.generate({
      versionId: lastResult.value?.versionId,
      label: '排程草稿 ' + store.filterDate,
      rangeStart: toUtcIso(genRange.value[0]),
      rangeEnd: toUtcIso(genRange.value[1]),
      stationIds: genStations.value.length ? genStations.value : undefined,
      satelliteIds: genSatellites.value.length ? genSatellites.value : undefined
    })
    ElMessage.success(`排程完成：入选 ${lastResult.value.scheduledCount}，落选 ${lastResult.value.rejectedCount}`)
    await loadDraft()
    await loadGantt()
    if (lastResult.value.rejectedCount > 0) tab.value = 'rejected'
  } catch (e) {
    handleApiError(e, '自动排程失败')
  } finally {
    generating.value = false
  }
}

// ---------- 预检 ----------
const prechecking = ref(false)
const precheckGenerating = ref(false)
const precheckReport = ref<PrecheckReport>()
const precheckReports = ref<PrecheckReport[]>([])

async function doPrecheck() {
  if (!genRange.value) {
    ElMessage.warning('请选择排程时间范围')
    return
  }
  prechecking.value = true
  try {
    precheckReport.value = await precheckApi.run({
      rangeStart: toUtcIso(genRange.value[0]),
      rangeEnd: toUtcIso(genRange.value[1]),
      stationIds: genStations.value.length ? genStations.value : undefined,
      satelliteIds: genSatellites.value.length ? genSatellites.value : undefined,
      operator: 'scheduler'
    })
    const s = precheckReport.value.summary
    if (precheckReport.value.cached) {
      ElMessage.info('命中同参数历史预检报告，未重复分析')
    } else {
      ElMessage.success(`预检完成：可排 ${s?.schedulableCount ?? 0}，必然落选 ${s?.rejectedCount ?? 0}`)
    }
    tab.value = 'precheck'
    await loadPrecheckList()
  } catch (e) {
    handleApiError(e, '预检分析失败')
  } finally {
    prechecking.value = false
  }
}

async function loadPrecheckList() {
  precheckReports.value = await precheckApi.list()
}

async function onPrecheckView(r: PrecheckReport) {
  precheckReport.value = await precheckApi.detail(r.id)
}

/** 依据预检生成草稿：始终新建草稿版本，不改变既有草稿。 */
async function onPrecheckGenerate(report: PrecheckReport) {
  precheckGenerating.value = true
  try {
    const { result } = await precheckApi.generate(report.id, 'scheduler')
    lastResult.value = result
    ElMessage.success(`已依据预检报告 #${report.id} 生成草稿 #${result.versionId}：` +
      `入选 ${result.scheduledCount}，落选 ${result.rejectedCount}`)
    await loadDraft()
    await loadGantt()
    tab.value = 'tasks'
  } catch (e) {
    handleApiError(e, '依据预检生成草稿失败')
  } finally {
    precheckGenerating.value = false
  }
}

/** 从预检摘要/明细跳转到冲突定位视图。 */
function onPrecheckLocate(conflicts: ConflictInfo[]) {
  activeConflicts.value = conflicts
  tab.value = 'gantt'
  flashLaneId.value = firstConflictStation()
}

/** 从预检摘要跳转到甘特图（定位到报告范围首日）或资源管理。 */
function onPrecheckGoto(view: 'gantt' | 'resources') {
  if (view === 'gantt') {
    if (precheckReport.value) {
      store.filterDate = precheckReport.value.rangeStart.slice(0, 10)
    }
    router.push('/gantt')
  } else {
    router.push('/resources')
  }
}

// ---------- 草稿详情 ----------
const detail = ref<{ version: any; tasks: PassTask[]; audits: any[] }>()

async function loadDraft() {
  if (!draftId.value) return
  detail.value = await scheduleApi.versionDetail(draftId.value, true)
}

const rejectedRows = computed(() => lastResult.value?.rejected ?? [])

function satCode(id: number) {
  return store.satellites.find(s => s.id === id)?.code ?? id
}
function stationAntennas(stationId?: number) {
  return stationId ? store.antennasOf(stationId) : []
}

// ---------- 调整 ----------
const adjustVisible = ref(false)
const saving = ref(false)
const adjustRange = ref<[Date, Date]>()
const adjustForm = ref<any>({})

function openAdjust(row: PassTask) {
  adjustForm.value = { ...row }
  adjustRange.value = [parseTime(row.startTime), parseTime(row.endTime)]
  adjustVisible.value = true
}

async function submitAdjust() {
  const f = adjustForm.value
  saving.value = true
  try {
    await scheduleApi.adjustTask(f.id, {
      antennaId: f.antennaId,
      startTime: adjustRange.value ? toUtcIso(adjustRange.value[0]) : undefined,
      endTime: adjustRange.value ? toUtcIso(adjustRange.value[1]) : undefined,
      priority: f.priority,
      remark: f.remark,
      version: f.version
    })
    ElMessage.success('调整已保存')
    adjustVisible.value = false
    activeConflicts.value = []
    await Promise.all([loadDraft(), loadGantt()])
  } catch (e) {
    handleApiError(e, '调整失败')
  } finally {
    saving.value = false
  }
}

// ---------- 取消 / 移除 ----------
async function onCancel(row: PassTask) {
  try {
    const { value } = await ElMessageBox.prompt('取消后窗口将被释放，可重新排程。填写取消原因：',
      `取消任务 #${row.id}`, { inputPlaceholder: '取消原因', confirmButtonText: '确认取消', cancelButtonText: '返回' })
    await scheduleApi.cancelTask(row.id, value || '操作员取消')
    ElMessage.success('任务已取消，窗口已释放为 AVAILABLE')
    activeConflicts.value = []
    await Promise.all([loadDraft(), loadGantt()])
  } catch (e: any) {
    if (e === 'cancel') return
    handleApiError(e, '取消失败')
  }
}

async function onRemove(row: PassTask) {
  try {
    await ElMessageBox.confirm(`从草稿中彻底移除任务 #${row.id}？窗口会同步释放。`, '确认移除', { type: 'warning' })
    await scheduleApi.removeTask(row.id)
    ElMessage.success('已移除')
    await Promise.all([loadDraft(), loadGantt()])
  } catch (e: any) {
    if (e === 'cancel') return
    handleApiError(e, '移除失败')
  }
}

// ---------- 手工添加 ----------
const addVisible = ref(false)
const addRange = ref<[Date, Date]>()
const addForm = ref<{ windowId?: number; antennaId?: number; priority?: number; remark?: string }>({})
const addableWindows = ref<VisibilityWindow[]>([])

async function openAddDialog() {
  if (!draftId.value) {
    ElMessage.warning('请先生成排程草稿')
    return
  }
  addForm.value = { antennaId: undefined, priority: 5, remark: '' }
  addRange.value = undefined
  const day = genRange.value ? utcDateOf(genRange.value[0]) : store.filterDate
  addableWindows.value = await resourceApi.windows({
    date: day,
    stationId: store.filterStationId,
    satelliteId: store.filterSatelliteId
  })
  addableWindows.value = addableWindows.value.filter(w => w.status === 'AVAILABLE')
  addVisible.value = true
}

const selectedWindow = computed(() => addableWindows.value.find(w => w.id === addForm.value.windowId))
const selectedWindowAntennas = computed(() =>
  selectedWindow.value ? store.antennasOf(selectedWindow.value.stationId) : [])

watch(() => addForm.value.windowId, () => {
  const w = selectedWindow.value
  if (w) {
    addRange.value = [parseTime(w.startTime), parseTime(w.endTime)]
    addForm.value.antennaId = w.preferredAntennaId
    addForm.value.priority = w.priority
  }
})

function manualAddRejected(row: any) {
  tab.value = 'tasks'
  openAddDialog().then(() => {
    addForm.value.windowId = row.windowId
  })
}

async function submitAdd() {
  if (!addForm.value.windowId || !addRange.value) {
    ElMessage.warning('请选择窗口和时间')
    return
  }
  saving.value = true
  try {
    await scheduleApi.createTask({
      versionId: draftId.value!,
      windowId: addForm.value.windowId,
      antennaId: addForm.value.antennaId,
      startTime: toUtcIso(addRange.value[0]),
      endTime: toUtcIso(addRange.value[1]),
      priority: addForm.value.priority,
      remark: addForm.value.remark
    })
    ElMessage.success('任务已提交')
    addVisible.value = false
    activeConflicts.value = []
    await Promise.all([loadDraft(), loadGantt()])
  } catch (e) {
    handleApiError(e, '提交失败')
  } finally {
    saving.value = false
  }
}

// ---------- 409 冲突处理与定位 ----------
const activeConflicts = ref<ConflictInfo[]>([])
const flashLaneId = ref<number>()
let flashIndex = 0

function handleApiError(e: unknown, fallback: string) {
  const err = e as ApiError
  if (err.status === 409 && err.code === 4090 && err.conflicts?.length) {
    activeConflicts.value = err.conflicts
    tab.value = 'gantt'
    ElMessageBox.alert(
      err.conflicts.map((c, i) => `${i + 1}. [${conflictLabel(c.type)}] ${c.reason}` +
        (c.overlapStart ? `（冲突时段 ${fmtDateTime(c.overlapStart)} ~ ${fmtTime(c.overlapEnd)} UTC）` : '')).join('\n'),
      '提交冲突：返回冲突对象与时间段',
      { type: 'error', confirmButtonText: '去冲突定位视图' }
    ).catch(() => {})
    flashLaneId.value = firstConflictStation()
  } else if (err.status === 409 && err.code === 4091) {
    ElMessageBox.confirm(
      `${err.message}\n\n该任务已被其他操作员调整，是否重新加载最新数据？`,
      '乐观锁冲突', { type: 'warning', confirmButtonText: '刷新数据', cancelButtonText: '取消' }
    ).then(() => loadDraft()).catch(() => {})
  } else if (err.status === 409 && err.code === 4093) {
    ElMessage.error('版本已发布不可修改：请在版本管理页基于该版本创建修订版')
  } else {
    ElMessage.error(err.message || fallback)
  }
}

function firstConflictStation(): number | undefined {
  const c = activeConflicts.value[0]
  return c?.stationId ?? c?.existingTask?.stationId
}

function locateConflict(c: ConflictInfo) {
  activeConflicts.value = [c]
  tab.value = 'gantt'
  flashLaneId.value = c.stationId ?? c.existingTask?.stationId
}

function flashNext() {
  if (!activeConflicts.value.length) return
  const c = activeConflicts.value[flashIndex % activeConflicts.value.length]
  flashLaneId.value = undefined
  setTimeout(() => {
    flashLaneId.value = c.stationId ?? c.existingTask?.stationId
    flashIndex++
  }, 50)
}

// ---------- 甘特数据 ----------
const ganttData = ref<GanttData>()
const ganttRangeStart = computed(() =>
  genRange.value ? `${utcDateOf(genRange.value[0])}T00:00:00Z` : `${store.filterDate}T00:00:00Z`)
const ganttRangeEnd = computed(() => addDaysUtc(ganttRangeStart.value.slice(0, 10), 1))

const ganttTasks = computed<PassTask[]>(() => {
  if (!detail.value) return []
  return detail.value.tasks.filter(t => t.status !== 'CANCELLED')
})

async function loadGantt() {
  ganttData.value = await resourceApi.gantt({
    date: ganttRangeStart.value.slice(0, 10),
    stationId: store.filterStationId,
    satelliteId: store.filterSatelliteId
  })
}

function utcDateOf(d: Date) {
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}-${String(d.getUTCDate()).padStart(2, '0')}`
}

// ---------- 展示辅助 ----------
function fmtFull(s?: string) {
  return s ? parseTime(s).toISOString().slice(0, 16).replace('T', ' ') : '-'
}
function rowClass({ row }: { row: PassTask }) {
  return row.status === 'CANCELLED' ? 'cancelled-row' : ''
}
function statusType(s: string) {
  return { DRAFT: 'warning', PUBLISHED: 'success', CANCELLED: 'info' }[s] as any
}
function statusLabel(s: string) {
  return { DRAFT: '草稿', PUBLISHED: '已发布', CANCELLED: '已取消' }[s] ?? s
}
function conflictLabel(t: string) {
  return CONFLICT_LABELS[t] ?? t
}
function conflictTagType(t: string) {
  return t === 'MAINTENANCE_BLOCK' ? 'warning' : 'danger'
}
function actionLabel(a: string) {
  return ACTION_LABELS[a] ?? a
}
function auditColor(a: string) {
  return ({
    GENERATE: 'primary', ADD: 'success', ADJUST: 'warning',
    CANCEL: 'info', REMOVE: 'danger', PUBLISH: 'success', REVISE: 'primary',
    PRECHECK_START: 'info', PRECHECK_DONE: 'success', PRECHECK_FAIL: 'danger',
    PRECHECK_GEN: 'primary'
  } as Record<string, string>)[a] as any
}
function pretty(json?: string) {
  if (!json) return '—'
  try {
    return JSON.stringify(JSON.parse(json), null, 2)
  } catch {
    return json
  }
}

watch(() => [store.filterDate, store.filterStationId, store.filterSatelliteId], () => {
  loadGantt()
}, { immediate: true })
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.gen-summary {
  margin-top: 10px;
}
.conflict-tag {
  margin: 2px 4px 2px 0;
  cursor: pointer;
}
.hint {
  margin-left: 10px;
  color: #909399;
  font-size: 12px;
}
.v-tag {
  color: #c0c4cc;
  font-size: 11px;
  margin-left: 6px;
}
.diff-title {
  font-weight: 600;
  margin-bottom: 6px;
}
.diff-title.before { color: #e6a23c; }
.diff-title.after { color: #67c23a; }
.diff-json {
  background: #f6f8fa;
  border-radius: 4px;
  padding: 8px;
  font-size: 11px;
  max-height: 320px;
  overflow: auto;
  margin: 0;
}
:deep(.cancelled-row) {
  color: #c0c4cc;
  text-decoration: line-through;
}
</style>
