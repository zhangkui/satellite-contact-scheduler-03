<template>
  <div class="page-container">
    <el-page-header content="发布确认" @back="$router.push('/versions')" class="section-card" />

    <el-card v-loading="loading" class="section-card">
      <template #header>
        <div class="card-header">
          <span>
            版本 #{{ versionId }}
            <el-tag v-if="detail" size="small" :type="statusType(detail.version.status)">
              {{ statusLabel(detail.version.status) }}
            </el-tag>
            <span v-if="detail?.version.versionNo" style="margin-left:8px">
              发布号 v{{ detail.version.versionNo }}
            </span>
          </span>
          <span class="range">
            排程范围(UTC)：{{ fmtFull(detail?.version.rangeStart) }} ~ {{ fmtFull(detail?.version.rangeEnd) }}
          </span>
        </div>
      </template>

      <el-alert v-if="detail?.version.status !== 'DRAFT'" type="error" :closable="false" show-icon
        title="该版本已发布，不能重复发布。"
        description="已发布版本内容不可变；如需调整，请点击下方按钮基于该版本创建修订草稿。" class="section-card">
        <template #default>
          <el-button type="warning" size="small" @click="revise">基于此版本创建修订版</el-button>
        </template>
      </el-alert>

      <el-row :gutter="16">
        <el-col :span="6">
          <el-statistic title="任务总数" :value="detail?.tasks.length ?? 0" />
        </el-col>
        <el-col :span="6">
          <el-statistic title="涉及地面站" :value="stationCount" />
        </el-col>
        <el-col :span="6">
          <el-statistic title="涉及卫星" :value="satelliteCount" />
        </el-col>
        <el-col :span="6">
          <el-statistic title="跨午夜任务" :value="crossMidnightCount" />
        </el-col>
      </el-row>

      <el-divider />

      <el-table :data="detail?.tasks ?? []" size="small" max-height="360">
        <el-table-column prop="satelliteCode" label="卫星" width="100" />
        <el-table-column prop="stationName" label="地面站" width="120" />
        <el-table-column prop="antennaCode" label="天线" width="80" />
        <el-table-column label="AOS–LOS (UTC)">
          <template #default="{ row }">
            <span class="mono">{{ fmtFull(row.startTime) }} ~ {{ fmtFull(row.endTime) }}</span>
            <el-tag v-if="row.crossMidnight" type="warning" size="small" effect="plain">跨午夜🌙</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="priority" label="优先级" width="80" />
      </el-table>

      <el-divider />

      <el-form :inline="true">
        <el-form-item label="发布操作人">
          <el-input v-model="operator" style="width: 180px" />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="confirmed">
            我已核对任务清单，发布后该版本冻结，只能通过新版本修订
          </el-checkbox>
        </el-form-item>
      </el-form>
      <el-button type="primary" size="large" :disabled="!canPublish" :loading="publishing"
                 @click="doPublish">
        确认发布
      </el-button>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { scheduleApi } from '@/api'
import type { ApiError } from '@/api/http'
import { parseTime } from '@/utils/time'

const route = useRoute()
const router = useRouter()
const versionId = Number(route.params.id)
const loading = ref(false)
const publishing = ref(false)
const detail = ref<{ version: any; tasks: any[]; audits: any[] }>()
const operator = ref('scheduler')
const confirmed = ref(false)

const canPublish = computed(() =>
  detail.value?.version.status === 'DRAFT' && confirmed.value)

const stationCount = computed(() => new Set((detail.value?.tasks ?? []).map(t => t.stationId)).size)
const satelliteCount = computed(() => new Set((detail.value?.tasks ?? []).map(t => t.satelliteId)).size)
const crossMidnightCount = computed(() =>
  (detail.value?.tasks ?? []).filter(t => t.crossMidnight).length)

async function load() {
  loading.value = true
  try {
    detail.value = await scheduleApi.versionDetail(versionId, false)
  } finally {
    loading.value = false
  }
}

async function doPublish() {
  publishing.value = true
  try {
    const res = await scheduleApi.publish(versionId, operator.value)
    ElMessage.success(`发布成功：排程版本 v${res.versionNo}，共 ${res.taskCount} 个任务`)
    await load()
  } catch (e) {
    const err = e as ApiError
    if (err.status === 409 && err.code === 4092) {
      // 重复发布：刷新后提示
      ElMessage.warning(err.message)
      await load()
    } else {
      ElMessage.error(err.message || '发布失败')
    }
  } finally {
    publishing.value = false
  }
}

async function revise() {
  const res = await scheduleApi.revise(versionId)
  ElMessage.success(`已创建修订草稿 #${res.versionId}`)
  router.push(`/publish/${res.versionId}`)
}

function fmtFull(s?: string) {
  return s ? parseTime(s).toISOString().slice(0, 16).replace('T', ' ') : '-'
}
function statusType(s: string) {
  return { DRAFT: 'warning', PUBLISHED: 'success', SUPERSEDED: 'info' }[s] as any
}
function statusLabel(s: string) {
  return { DRAFT: '草稿', PUBLISHED: '已发布', SUPERSEDED: '已取代' }[s] ?? s
}

watch(() => route.params.id, load, { immediate: true })
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.range {
  color: #909399;
  font-size: 12px;
}
</style>
