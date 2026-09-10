<template>
  <div class="page-container">
    <el-card class="section-card">
      <template #header>
        <div class="card-header">
          <span>过站窗口甘特图（UTC 日期：{{ store.filterDate }}）</span>
          <el-radio-group v-model="taskLayer" size="small">
            <el-radio-button value="draft">草稿任务</el-radio-button>
            <el-radio-button value="published">已发布任务</el-radio-button>
            <el-radio-button value="none">仅窗口</el-radio-button>
          </el-radio-group>
        </div>
      </template>

      <div v-loading="loading">
        <GanttChart
          :range-start="`${store.filterDate}T00:00:00Z`"
          :range-end="nextDay"
          :stations="shownStations"
          :windows="data?.windows ?? []"
          :tasks="shownTasks"
          :maintenance="data?.maintenance ?? []"
          :tz-mode="store.displayTz"
        />
      </div>
    </el-card>

    <el-row :gutter="16">
      <el-col :span="14">
        <el-card>
          <template #header>可见窗口（{{ data?.windows.length ?? 0 }}）</template>
          <el-table :data="data?.windows ?? []" size="small" height="320">
            <el-table-column prop="satelliteCode" label="卫星" width="90" />
            <el-table-column prop="stationName" label="地面站" width="110" />
            <el-table-column label="AOS–LOS (UTC)" width="150">
              <template #default="{ row }">
                {{ fmtTime(row.startTime) }}–{{ fmtTime(row.endTime) }}
                <el-tag v-if="row.crossMidnight" size="small" type="warning" effect="plain">跨午夜🌙</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="priority" label="优先级" width="70" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 'AVAILABLE' ? 'success' : 'info'" size="small">
                  {{ row.status === 'AVAILABLE' ? '可排' : '占用' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card>
          <template #header>
            维护封锁（{{ data?.maintenance.length ?? 0 }}）
            <el-tag v-if="data?.publishedVersion" size="small" type="success" style="margin-left: 8px">
              当前发布 v{{ data.publishedVersion.versionNo }}
            </el-tag>
            <el-tag v-if="data?.draftVersion" size="small" type="warning" style="margin-left: 6px">
              有草稿 #{{ data.draftVersion.id }}
            </el-tag>
          </template>
          <el-table :data="data?.maintenance ?? []" size="small" height="320">
            <el-table-column label="地面站" width="100">
              <template #default="{ row }">{{ store.stationName(row.stationId) }}</template>
            </el-table-column>
            <el-table-column label="封锁时段 (UTC)" width="150">
              <template #default="{ row }">
                {{ fmtTime(row.startTime) }}–{{ fmtTime(row.endTime) }}
              </template>
            </el-table-column>
            <el-table-column prop="reason" label="原因" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import GanttChart from '@/components/GanttChart.vue'
import { resourceApi } from '@/api'
import type { GanttData, PassTask } from '@/types'
import { useResourceStore } from '@/stores/resource'
import { addDaysUtc, fmtTime } from '@/utils/time'

const store = useResourceStore()
const data = ref<GanttData>()
const loading = ref(false)
const taskLayer = ref<'draft' | 'published' | 'none'>('draft')

const nextDay = computed(() => addDaysUtc(store.filterDate, 1))

const shownStations = computed(() => {
  if (store.filterStationId) {
    return store.stations.filter(s => s.id === store.filterStationId)
  }
  return data.value?.stations ?? store.stations
})

const shownTasks = computed<PassTask[]>(() => {
  if (!data.value || taskLayer.value === 'none') return []
  const list = taskLayer.value === 'draft' ? data.value.draftTasks : data.value.publishedTasks
  return list.filter(t => t.status !== 'CANCELLED')
})

async function load() {
  loading.value = true
  try {
    data.value = await resourceApi.gantt({
      date: store.filterDate,
      stationId: store.filterStationId,
      satelliteId: store.filterSatelliteId
    })
  } finally {
    loading.value = false
  }
}

watch(() => [store.filterDate, store.filterStationId, store.filterSatelliteId], load, { immediate: true })
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
