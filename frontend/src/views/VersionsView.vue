<template>
  <div class="page-container">
    <el-card class="section-card">
      <template #header>
        <div class="card-header">
          <span>排程版本</span>
          <el-button size="small" @click="load">刷新</el-button>
        </div>
      </template>
      <el-table :data="versions" size="small" v-loading="loading">
        <el-table-column label="版本" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.versionNo" type="success" size="small">v{{ row.versionNo }}</el-tag>
            <el-tag v-else type="warning" size="small">草稿</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
            <el-tag v-if="row.baseVersionId" size="small" type="info" effect="plain" style="margin-left:4px">
              修订自 #{{ row.baseVersionId }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="label" label="说明" show-overflow-tooltip />
        <el-table-column prop="taskCount" label="任务数" width="80" />
        <el-table-column label="发布时间(UTC)" width="170">
          <template #default="{ row }">{{ fmtDateTime(row.publishedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'DRAFT'" link type="primary" size="small"
                       @click="$router.push(`/publish/${row.id}`)">去发布</el-button>
            <el-button v-if="row.status !== 'DRAFT'" link type="primary" size="small"
                       @click="revise(row)">创建修订版</el-button>
            <el-button link type="primary" size="small"
                       @click="selectForCompare(row.id)">选择对比</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card>
      <template #header>版本对比</template>
      <el-form :inline="true">
        <el-form-item label="基准版本">
          <el-select v-model="leftId" placeholder="选择左版本" style="width: 200px">
            <el-option v-for="v in versionOptions" :key="'l' + v.id"
                       :label="versionOptionLabel(v)" :value="v.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="对比版本">
          <el-select v-model="rightId" placeholder="选择右版本" style="width: 200px">
            <el-option v-for="v in versionOptions" :key="'r' + v.id"
                       :label="versionOptionLabel(v)" :value="v.id" />
          </el-select>
        </el-form-item>
        <el-button type="primary" :disabled="!leftId || !rightId || leftId === rightId"
                   :loading="comparing" @click="doCompare">开始对比</el-button>
      </el-form>

      <div v-if="compareResult">
        <el-descriptions :column="4" border size="small" class="cmp-summary">
          <el-descriptions-item label="基准">
            {{ metaLabel(compareResult.left) }}
          </el-descriptions-item>
          <el-descriptions-item label="对比">
            {{ metaLabel(compareResult.right) }}
          </el-descriptions-item>
          <el-descriptions-item>
            <template #label>
              新增 <el-tag type="success" size="small">{{ compareResult.summary.added }}</el-tag>
              删除 <el-tag type="danger" size="small">{{ compareResult.summary.removed }}</el-tag>
            </template>
          </el-descriptions-item>
          <el-descriptions-item>
            <template #label>
              修改 <el-tag type="warning" size="small">{{ compareResult.summary.changed }}</el-tag>
              未变 <el-tag type="info" size="small">{{ compareResult.summary.unchanged }}</el-tag>
            </template>
          </el-descriptions-item>
        </el-descriptions>

        <el-tabs>
          <el-tab-pane :label="`修改 (${compareResult.changed.length})`">
            <el-table :data="compareResult.changed" size="small">
              <el-table-column prop="satelliteCode" label="卫星" width="90" />
              <el-table-column prop="stationCode" label="地面站" width="90" />
              <el-table-column label="字段变更">
                <template #default="{ row }">
                  <div v-for="(v, k) in row.after" :key="k" class="diff-line">
                    <b>{{ fieldLabel(k) }}</b>：
                    <span class="old-val">{{ displayVal(k, row.before[k]) }}</span>
                    <el-icon><Right /></el-icon>
                    <span class="new-val">{{ displayVal(k, v) }}</span>
                  </div>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`新增 (${compareResult.added.length})`">
            <el-table :data="compareResult.added" size="small">
              <el-table-column prop="satelliteCode" label="卫星" width="90" />
              <el-table-column prop="stationCode" label="地面站" width="90" />
              <el-table-column label="时段(UTC)">
                <template #default="{ row }">{{ fmtDateTime(row.startTime) }} ~ {{ fmtTime(row.endTime) }}</template>
              </el-table-column>
              <el-table-column prop="antennaCode" label="天线" width="80" />
              <el-table-column prop="priority" label="优先级" width="70" />
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`删除 (${compareResult.removed.length})`">
            <el-table :data="compareResult.removed" size="small">
              <el-table-column prop="satelliteCode" label="卫星" width="90" />
              <el-table-column prop="stationCode" label="地面站" width="90" />
              <el-table-column label="时段(UTC)">
                <template #default="{ row }">{{ fmtDateTime(row.startTime) }} ~ {{ fmtTime(row.endTime) }}</template>
              </el-table-column>
              <el-table-column prop="antennaCode" label="天线" width="80" />
              <el-table-column prop="priority" label="优先级" width="70" />
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { scheduleApi } from '@/api'
import type { ScheduleVersion } from '@/types'
import { fmtDateTime, fmtTime } from '@/utils/time'

const router = useRouter()
const versions = ref<ScheduleVersion[]>([])
const loading = ref(false)
const leftId = ref<number>()
const rightId = ref<number>()
const comparing = ref(false)
const compareResult = ref<any>()

const versionOptions = computed(() =>
  [...versions.value].sort((a, b) => (b.versionNo ?? 0) - (a.versionNo ?? 0)))

async function load() {
  loading.value = true
  try {
    versions.value = await scheduleApi.versions()
  } finally {
    loading.value = false
  }
}

function versionOptionLabel(v: ScheduleVersion) {
  return v.versionNo ? `v${v.versionNo}（${v.status}）` : `草稿 #${v.id}`
}
function metaLabel(m: any) {
  return m.versionNo ? `v${m.versionNo} · ${m.status}` : `草稿 #${m.versionId}`
}

function statusType(s: string) {
  return { DRAFT: 'warning', PUBLISHED: 'success', SUPERSEDED: 'info' }[s] as any
}
function statusLabel(s: string) {
  return { DRAFT: '草稿', PUBLISHED: '已发布', SUPERSEDED: '已取代' }[s] ?? s
}

async function revise(row: ScheduleVersion) {
  try {
    const res = await scheduleApi.revise(row.id)
    ElMessage.success(`已创建修订草稿 #${res.versionId}，原版本保持不可变`)
    await load()
    router.push(`/publish/${res.versionId}`)
  } catch (e: any) {
    ElMessage.error(e.message)
  }
}

function selectForCompare(id: number) {
  if (!leftId.value) {
    leftId.value = id
  } else {
    rightId.value = id
  }
}

async function doCompare() {
  comparing.value = true
  try {
    compareResult.value = await scheduleApi.compare(leftId.value!, rightId.value!)
  } finally {
    comparing.value = false
  }
}

const FIELD_LABELS: Record<string, string> = {
  startTime: '开始时间', endTime: '结束时间', antennaId: '天线ID', antennaCode: '天线',
  priority: '优先级', status: '状态', remark: '备注'
}
function fieldLabel(k: any) {
  return FIELD_LABELS[k] ?? k
}
function displayVal(k: any, v: any) {
  if (v === null || v === undefined || v === '') return '—'
  if (k === 'startTime' || k === 'endTime') return fmtDateTime(String(v))
  return String(v)
}

onMounted(load)
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.cmp-summary {
  margin: 10px 0;
}
.diff-line {
  font-size: 12px;
  line-height: 20px;
}
.old-val {
  color: #f56c6c;
  text-decoration: line-through;
  margin: 0 4px;
}
.new-val {
  color: #67c23a;
  margin-left: 4px;
}
</style>
