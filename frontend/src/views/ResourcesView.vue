<template>
  <div class="page-container">
    <el-card v-loading="store.loading">
      <el-tabs v-model="tab">
        <!-- 卫星 -->
        <el-tab-pane label="卫星" name="satellites">
          <el-button type="primary" size="small" class="mb10" @click="editSat({})">新增卫星</el-button>
          <el-table :data="store.satellites" size="small">
            <el-table-column prop="code" label="编号" width="120" />
            <el-table-column prop="name" label="名称" />
            <el-table-column prop="band" label="频段" width="80" />
            <el-table-column prop="orbitType" label="轨道" width="80" />
            <el-table-column prop="priority" label="默认优先级" width="100" />
            <el-table-column label="启用" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.enabled ? 'success' : 'info'">
                  {{ row.enabled ? '是' : '否' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="editSat(row)">编辑</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 地面站 -->
        <el-tab-pane label="地面站" name="stations">
          <el-button type="primary" size="small" class="mb10" @click="editStation({})">新增地面站</el-button>
          <el-table :data="store.stations" size="small">
            <el-table-column prop="code" label="编号" width="120" />
            <el-table-column prop="name" label="名称" />
            <el-table-column prop="region" label="地区" />
            <el-table-column prop="timezone" label="IANA 时区" width="160" />
            <el-table-column label="经纬度" width="180">
              <template #default="{ row }">{{ row.longitude }}, {{ row.latitude }}</template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="editStation(row)">编辑</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 天线 -->
        <el-tab-pane label="天线资源" name="antennas">
          <el-button type="primary" size="small" class="mb10" @click="editAntenna({})">新增天线</el-button>
          <el-table :data="store.antennas" size="small">
            <el-table-column label="所属地面站">
              <template #default="{ row }">{{ store.stationName(row.stationId) }}</template>
            </el-table-column>
            <el-table-column prop="code" label="编号" width="100" />
            <el-table-column prop="name" label="名称" />
            <el-table-column prop="band" label="频段" width="90" />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="antennaStatusType(row.status)">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="editAntenna(row)">编辑</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 可见窗口 -->
        <el-tab-pane :label="`可见窗口 (${windows.length})`" name="windows">
          <div class="toolbar">
            <el-button type="primary" size="small" @click="editWindow({})">新增窗口</el-button>
            <span class="tip">窗口时间一律填写 UTC（跨午夜直接选择次日结束时刻）</span>
          </div>
          <el-table :data="windows" size="small">
            <el-table-column prop="satelliteCode" label="卫星" width="100" />
            <el-table-column prop="stationName" label="地面站" width="120" />
            <el-table-column prop="preferredAntennaCode" label="首选天线" width="90" />
            <el-table-column label="AOS–LOS (UTC)" width="280">
              <template #default="{ row }">
                <span class="mono">{{ fmtFull(row.startTime) }} ~ {{ fmtFull(row.endTime) }}</span>
                <el-tag v-if="row.crossMidnight" size="small" type="warning" effect="plain">跨午夜🌙</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="priority" label="优先级" width="70" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'AVAILABLE' ? 'success' : 'info'">
                  {{ row.status === 'AVAILABLE' ? '可排' : '占用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="130">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="editWindow(row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="removeWindow(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 维护封锁 -->
        <el-tab-pane :label="`维护封锁 (${maintenance.length})`" name="maintenance">
          <el-button type="primary" size="small" class="mb10" @click="editMaint({})">新增封锁区间</el-button>
          <el-table :data="maintenance" size="small">
            <el-table-column label="地面站" width="130">
              <template #default="{ row }">{{ store.stationName(row.stationId) }}</template>
            </el-table-column>
            <el-table-column label="天线" width="110">
              <template #default="{ row }">
                {{ row.antennaId ? antennaCode(row.antennaId) : '全站封锁' }}
              </template>
            </el-table-column>
            <el-table-column label="封锁时段 (UTC)" width="280">
              <template #default="{ row }">
                <span class="mono">{{ fmtFull(row.startTime) }} ~ {{ fmtFull(row.endTime) }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="reason" label="原因" />
            <el-table-column label="操作" width="130">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="editMaint(row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="removeMaint(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 卫星编辑 -->
    <el-dialog v-model="satDlg" :title="satForm.id ? '编辑卫星' : '新增卫星'" width="460px">
      <el-form :model="satForm" label-width="90px">
        <el-form-item label="编号"><el-input v-model="satForm.code" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="satForm.name" /></el-form-item>
        <el-form-item label="频段">
          <el-select v-model="satForm.band">
            <el-option label="S" value="S" /><el-option label="X" value="X" />
            <el-option label="S/X" value="S/X" />
          </el-select>
        </el-form-item>
        <el-form-item label="轨道类型"><el-input v-model="satForm.orbitType" /></el-form-item>
        <el-form-item label="优先级"><el-input-number v-model="satForm.priority" :min="1" :max="10" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="satForm.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="satDlg = false">取消</el-button>
        <el-button type="primary" @click="saveSat">保存</el-button>
      </template>
    </el-dialog>

    <!-- 地面站编辑 -->
    <el-dialog v-model="stationDlg" :title="stationForm.id ? '编辑地面站' : '新增地面站'" width="460px">
      <el-form :model="stationForm" label-width="90px">
        <el-form-item label="编号"><el-input v-model="stationForm.code" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="stationForm.name" /></el-form-item>
        <el-form-item label="地区"><el-input v-model="stationForm.region" /></el-form-item>
        <el-form-item label="时区(IANA)">
          <el-select v-model="stationForm.timezone" filterable allow-create default-first-option>
            <el-option v-for="tz in TZ_OPTIONS" :key="tz" :label="tz" :value="tz" />
          </el-select>
        </el-form-item>
        <el-form-item label="经度"><el-input-number v-model="stationForm.longitude" :precision="2" /></el-form-item>
        <el-form-item label="纬度"><el-input-number v-model="stationForm.latitude" :precision="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="stationDlg = false">取消</el-button>
        <el-button type="primary" @click="saveStation">保存</el-button>
      </template>
    </el-dialog>

    <!-- 天线编辑 -->
    <el-dialog v-model="antennaDlg" :title="antennaForm.id ? '编辑天线' : '新增天线'" width="460px">
      <el-form :model="antennaForm" label-width="90px">
        <el-form-item label="地面站">
          <el-select v-model="antennaForm.stationId">
            <el-option v-for="s in store.stations" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="编号"><el-input v-model="antennaForm.code" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="antennaForm.name" /></el-form-item>
        <el-form-item label="频段">
          <el-select v-model="antennaForm.band">
            <el-option label="S" value="S" /><el-option label="X" value="X" />
            <el-option label="S/X" value="S/X" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="antennaForm.status">
            <el-option label="启用 ENABLED" value="ENABLED" />
            <el-option label="停用 DISABLED" value="DISABLED" />
            <el-option label="维护 MAINTENANCE" value="MAINTENANCE" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="antennaDlg = false">取消</el-button>
        <el-button type="primary" @click="saveAntenna">保存</el-button>
      </template>
    </el-dialog>

    <!-- 窗口编辑 -->
    <el-dialog v-model="windowDlg" :title="windowForm.id ? '编辑可见窗口' : '新增可见窗口'" width="520px">
      <el-form :model="windowForm" label-width="100px">
        <el-form-item label="卫星">
          <el-select v-model="windowForm.satelliteId">
            <el-option v-for="s in store.satellites" :key="s.id"
                       :label="`${s.code} ${s.name}`" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="地面站">
          <el-select v-model="windowForm.stationId">
            <el-option v-for="s in store.stations" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="首选天线">
          <el-select v-model="windowForm.preferredAntennaId" clearable>
            <el-option v-for="a in store.antennasOf(windowForm.stationId ?? 0)" :key="a.id"
                       :label="`${a.code} (${a.band})`" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="起止(UTC)">
          <el-date-picker v-model="windowRange" type="datetimerange" range-separator="至"
                          style="width: 100%" />
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="windowForm.priority" :min="1" :max="10" />
        </el-form-item>
        <el-form-item label="最大仰角">
          <el-input-number v-model="windowForm.maxElevation" :precision="1" :min="0" :max="90" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="windowDlg = false">取消</el-button>
        <el-button type="primary" @click="saveWindow">保存</el-button>
      </template>
    </el-dialog>

    <!-- 维护编辑 -->
    <el-dialog v-model="maintDlg" :title="maintForm.id ? '编辑封锁区间' : '新增封锁区间'" width="520px">
      <el-form :model="maintForm" label-width="100px">
        <el-form-item label="地面站">
          <el-select v-model="maintForm.stationId">
            <el-option v-for="s in store.stations" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="天线(空=全站)">
          <el-select v-model="maintForm.antennaId" clearable>
            <el-option v-for="a in store.antennasOf(maintForm.stationId ?? 0)" :key="a.id"
                       :label="`${a.code} ${a.name}`" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="封锁起止(UTC)">
          <el-date-picker v-model="maintRange" type="datetimerange" range-separator="至"
                          style="width: 100%" />
        </el-form-item>
        <el-form-item label="原因"><el-input v-model="maintForm.reason" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="maintDlg = false">取消</el-button>
        <el-button type="primary" @click="saveMaint">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { resourceApi } from '@/api'
import type { Antenna, MaintenanceBlock, VisibilityWindow } from '@/types'
import { useResourceStore } from '@/stores/resource'
import { parseTime, toUtcIso } from '@/utils/time'
import http from '@/api/http'

const store = useResourceStore()
const tab = ref('windows')
const TZ_OPTIONS = ['UTC', 'Asia/Shanghai', 'Asia/Urumqi', 'Europe/London', 'America/New_York']

const windows = ref<VisibilityWindow[]>([])
const maintenance = ref<MaintenanceBlock[]>([])

async function loadDayData() {
  const [w, m] = await Promise.all([
    resourceApi.windows({
      date: store.filterDate,
      stationId: store.filterStationId,
      satelliteId: store.filterSatelliteId
    }),
    resourceApi.maintenance({ date: store.filterDate, stationId: store.filterStationId })
  ])
  windows.value = w
  maintenance.value = m
}
watch(() => [store.filterDate, store.filterStationId, store.filterSatelliteId], loadDayData, { immediate: true })

function fmtFull(s?: string) {
  return s ? parseTime(s).toISOString().slice(0, 16).replace('T', ' ') : '-'
}
function antennaCode(id: number) {
  return store.antennas.find(a => a.id === id)?.code ?? id
}
function antennaStatusType(s: string) {
  return { ENABLED: 'success', DISABLED: 'info', MAINTENANCE: 'warning' }[s] as any
}

// ---- 卫星 ----
const satDlg = ref(false)
const satForm = ref<any>({})
function editSat(row: any) {
  satForm.value = { enabled: true, band: 'S', orbitType: 'LEO', priority: 5, ...row }
  satDlg.value = true
}
async function saveSat() {
  await http.post('/api/satellites', satForm.value)
  ElMessage.success('已保存')
  satDlg.value = false
  await store.loadAll()
}

// ---- 地面站 ----
const stationDlg = ref(false)
const stationForm = ref<any>({})
function editStation(row: any) {
  stationForm.value = { timezone: 'Asia/Shanghai', enabled: true, ...row }
  stationDlg.value = true
}
async function saveStation() {
  await http.post('/api/stations', stationForm.value)
  ElMessage.success('已保存')
  stationDlg.value = false
  await store.loadAll()
}

// ---- 天线 ----
const antennaDlg = ref(false)
const antennaForm = ref<any>({})
function editAntenna(row: any) {
  antennaForm.value = { band: 'S/X', status: 'ENABLED', stationId: store.stations[0]?.id, ...row }
  antennaDlg.value = true
}
async function saveAntenna() {
  await http.post('/api/antennas', antennaForm.value)
  ElMessage.success('已保存')
  antennaDlg.value = false
  await store.loadAll()
}

// ---- 窗口 ----
const windowDlg = ref(false)
const windowForm = ref<any>({})
const windowRange = ref<[Date, Date]>()
function editWindow(row: any) {
  windowForm.value = {
    satelliteId: store.satellites[0]?.id,
    stationId: store.filterStationId ?? store.stations[0]?.id,
    priority: 5,
    ...row
  }
  windowRange.value = row.startTime
    ? [parseTime(row.startTime), parseTime(row.endTime)]
    : undefined
  windowDlg.value = true
}
async function saveWindow() {
  if (!windowRange.value) {
    ElMessage.warning('请选择起止时间')
    return
  }
  await resourceApi.saveWindow({
    ...windowForm.value,
    startTime: toUtcIso(windowRange.value[0]),
    endTime: toUtcIso(windowRange.value[1])
  })
  ElMessage.success('窗口已保存')
  windowDlg.value = false
  await loadDayData()
}
async function removeWindow(row: VisibilityWindow) {
  await ElMessageBox.confirm(`删除窗口 #${row.id}？`, '确认', { type: 'warning' })
  await resourceApi.deleteWindow(row.id)
  ElMessage.success('已删除')
  await loadDayData()
}

// ---- 维护 ----
const maintDlg = ref(false)
const maintForm = ref<any>({})
const maintRange = ref<[Date, Date]>()
function editMaint(row: any) {
  maintForm.value = { stationId: store.filterStationId ?? store.stations[0]?.id, ...row }
  maintRange.value = row.startTime ? [parseTime(row.startTime), parseTime(row.endTime)] : undefined
  maintDlg.value = true
}
async function saveMaint() {
  if (!maintRange.value) {
    ElMessage.warning('请选择封锁时段')
    return
  }
  await resourceApi.saveMaintenance({
    ...maintForm.value,
    startTime: toUtcIso(maintRange.value[0]),
    endTime: toUtcIso(maintRange.value[1])
  })
  ElMessage.success('封锁区间已保存')
  maintDlg.value = false
  await loadDayData()
}
async function removeMaint(row: MaintenanceBlock) {
  await ElMessageBox.confirm(`删除封锁区间 #${row.id}？`, '确认', { type: 'warning' })
  await resourceApi.deleteMaintenance(row.id)
  ElMessage.success('已删除')
  await loadDayData()
}
</script>

<style scoped>
.mb10 {
  margin-bottom: 10px;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}
.tip {
  color: #909399;
  font-size: 12px;
}
</style>
