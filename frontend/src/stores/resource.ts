import { defineStore } from 'pinia'
import { ref } from 'vue'
import { resourceApi } from '@/api'
import type { Antenna, GroundStation, Satellite } from '@/types'
import { utcDate } from '@/utils/time'

/** 全局资源与筛选条件（按卫星 / 地面站 / 日期筛选，全站共享）。 */
export const useResourceStore = defineStore('resource', () => {
  const satellites = ref<Satellite[]>([])
  const stations = ref<GroundStation[]>([])
  const antennas = ref<Antenna[]>([])
  const loading = ref(false)

  // 筛选条件（UTC 日期）
  const filterDate = ref(utcDate())
  const filterStationId = ref<number | undefined>(undefined)
  const filterSatelliteId = ref<number | undefined>(undefined)
  // 展示时区：UTC 或选中地面站本地时区
  const displayTz = ref<'UTC' | 'LOCAL'>('UTC')

  async function loadAll() {
    loading.value = true
    try {
      const [sats, sts, ants] = await Promise.all([
        resourceApi.satellites(),
        resourceApi.stations(),
        resourceApi.antennas()
      ])
      satellites.value = sats
      stations.value = sts
      antennas.value = ants
    } finally {
      loading.value = false
    }
  }

  function stationName(id?: number) {
    return stations.value.find(s => s.id === id)?.name ?? String(id ?? '-')
  }

  function satelliteName(id?: number) {
    return satellites.value.find(s => s.id === id)?.name ?? String(id ?? '-')
  }

  function antennasOf(stationId: number) {
    return antennas.value.filter(a => a.stationId === stationId)
  }

  /** 当前展示用 IANA 时区。 */
  function effectiveTz(stationId?: number): string {
    if (displayTz.value === 'UTC') return 'UTC'
    if (stationId) {
      return stations.value.find(s => s.id === stationId)?.timezone || 'UTC'
    }
    return 'Asia/Shanghai'
  }

  return {
    satellites, stations, antennas, loading,
    filterDate, filterStationId, filterSatelliteId, displayTz,
    loadAll, stationName, satelliteName, antennasOf, effectiveTz
  }
})
