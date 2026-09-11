import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import PrecheckPanel from '@/components/PrecheckPanel.vue'
import { useResourceStore } from '@/stores/resource'
import type { ConflictInfo, PrecheckReport, PrecheckWindowDetail } from '@/types'

/**
 * 预检面板交互测试：指标摘要渲染、跨午夜边界标记、无可用天线、
 * 已有任务占用、冲突定位与“依据预检生成草稿”等操作。
 */

function conflict(overrides: Partial<ConflictInfo>): ConflictInfo {
  return { type: 'STATION_OVERLAP', reason: '', ...overrides }
}

function windowDetail(overrides: Partial<PrecheckWindowDetail>): PrecheckWindowDetail {
  return {
    windowId: 1001,
    satelliteId: 1,
    stationId: 100,
    startTime: '2026-09-10T03:00:00Z',
    endTime: '2026-09-10T03:15:00Z',
    priority: 5,
    crossMidnight: false,
    windowStatus: 'AVAILABLE',
    verdict: 'REJECTED',
    conflicts: [],
    ...overrides
  }
}

function makeReport(overrides: Partial<PrecheckReport> = {}): PrecheckReport {
  return {
    id: 12,
    requestHash: 'hash-12',
    rangeStart: '2026-09-10T00:00:00Z',
    rangeEnd: '2026-09-10T23:59:00Z',
    stationIds: [],
    satelliteIds: [],
    dataVersion: 'windows=3@2026-09-10 01:00:00;tasks=1@-',
    status: 'COMPLETED',
    totalWindows: 3,
    schedulableCount: 1,
    rejectedCount: 2,
    operator: 'scheduler',
    createdAt: '2026-09-10T01:00:00Z',
    summary: {
      totalWindows: 3,
      schedulableCount: 1,
      rejectedCount: 2,
      crossMidnightWindows: 1,
      rangeCrossesMidnight: false,
      conflictTypeCounts: { MAINTENANCE_BLOCK: 1, STATION_OVERLAP: 1 },
      affectedResources: {
        stationIds: [100], antennaIds: [101], satelliteIds: [1],
        maintenanceBlockIds: [9], blockingTaskIds: [55]
      },
      warnings: []
    },
    windows: [],
    ...overrides
  }
}

function mountPanel(report: PrecheckReport | null, reports: PrecheckReport[] = []) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useResourceStore()
  store.stations = [
    { id: 100, code: 'BJ', name: '北京站', timezone: 'Asia/Shanghai', enabled: true }
  ]
  return mount(PrecheckPanel, {
    props: { report, reports },
    global: { plugins: [ElementPlus] }
  })
}

beforeEach(() => setActivePinia(createPinia()))

describe('指标摘要与预检规则输出', () => {
  it('渲染窗口总数/可排/必然落选/跨午夜指标与冲突类型、受影响资源', () => {
    const wrapper = mountPanel(makeReport())
    expect(wrapper.find('[data-test="m-total"]').text()).toBe('3')
    expect(wrapper.find('[data-test="m-schedulable"]').text()).toBe('1')
    expect(wrapper.find('[data-test="m-rejected"]').text()).toBe('2')
    expect(wrapper.find('[data-test="m-cross"]').text()).toBe('1')

    const types = wrapper.find('[data-test="conflict-types"]')
    expect(types.text()).toContain('维护封锁 × 1')
    expect(types.text()).toContain('同站时间重叠 × 1')

    const affected = wrapper.find('[data-test="affected-resources"]')
    expect(affected.text()).toContain('地面站 × 1')
    expect(affected.text()).toContain('天线 × 1')
    expect(affected.text()).toContain('维护封锁 × 1')
    expect(affected.text()).toContain('占用任务 × 1')

    // 报告头：范围、数据版本、操作人
    expect(wrapper.text()).toContain('预检报告 #12')
    expect(wrapper.text()).toContain('2026-09-10 00:00')
    expect(wrapper.text()).toContain('windows=3@')
    expect(wrapper.text()).toContain('scheduler')
  })

  it('空范围时渲染告警与零指标', () => {
    const report = makeReport({
      totalWindows: 0,
      schedulableCount: 0,
      rejectedCount: 0,
      summary: {
        totalWindows: 0, schedulableCount: 0, rejectedCount: 0,
        crossMidnightWindows: 0, rangeCrossesMidnight: false,
        conflictTypeCounts: {},
        affectedResources: {
          stationIds: [], antennaIds: [], satelliteIds: [],
          maintenanceBlockIds: [], blockingTaskIds: []
        },
        warnings: ['排程范围 [2026-09-10T00:00 ~ 2026-09-10T23:59] 内没有可见窗口（空范围）']
      },
      windows: []
    })
    const wrapper = mountPanel(report)
    expect(wrapper.find('[data-test="m-total"]').text()).toBe('0')
    expect(wrapper.find('[data-test="warning"]').text()).toContain('空范围')
    expect(wrapper.find('[data-test="all-clear"]').exists()).toBe(true)
    // 无落选窗口时冲突定位按钮禁用
    expect(wrapper.find('[data-test="btn-locate"]').attributes('disabled')).toBeDefined()
  })
})

describe('跨午夜边界', () => {
  it('跨午夜落选窗口带标记，冲突标签展示跨日时段', async () => {
    const report = makeReport({
      windows: [
        windowDetail({
          windowId: 2002,
          startTime: '2026-09-10T23:50:00Z',
          endTime: '2026-09-11T00:15:00Z',
          crossMidnight: true,
          conflicts: [conflict({
            type: 'STATION_OVERLAP',
            reason: '与本次预检拟入选窗口[id=2001]时间重叠，同一地面站禁止重叠排程',
            overlapStart: '2026-09-10T23:50:00Z',
            overlapEnd: '2026-09-11T00:05:00Z',
            stationId: 100
          })]
        })
      ]
    })
    const wrapper = mountPanel(report)
    const row = wrapper.find('[data-test="conflict-row-2002"]')
    expect(row.find('[data-test="cross-midnight"]').text()).toContain('跨午夜')
    const tag = row.find('[data-test="conflict-tag-STATION_OVERLAP"]')
    expect(tag.text()).toContain('同站时间重叠')
    expect(tag.text()).toContain('23:50')
    expect(tag.text()).toContain('00:05')
    // 点击冲突标签 → 通知父级定位该冲突
    await tag.trigger('click')
    const located = wrapper.emitted('locate')
    expect(located).toBeTruthy()
    expect(located![0][0]).toHaveLength(1)
    expect((located![0][0] as ConflictInfo[])[0].type).toBe('STATION_OVERLAP')
  })
})

describe('无可用天线', () => {
  it('渲染天线能力不足冲突并定位到资源与窗口', () => {
    const report = makeReport({
      windows: [
        windowDetail({
          windowId: 3001,
          conflicts: [conflict({
            type: 'ANTENNA_CAPABILITY',
            reason: '地面站[北京站]没有 ENABLED 且频段[S]匹配的天线，窗口[id=3001]必然落选',
            stationId: 100
          })]
        })
      ]
    })
    const wrapper = mountPanel(report)
    const row = wrapper.find('[data-test="conflict-row-3001"]')
    expect(row.find('[data-test="conflict-tag-ANTENNA_CAPABILITY"]').text()).toContain('天线能力不足')
    expect(row.text()).toContain('北京站')
    expect(row.text()).toContain('3001')
  })
})

describe('已有任务占用', () => {
  it('渲染窗口已安排与同站重叠冲突，冲突定位聚合全部冲突', async () => {
    const report = makeReport({
      windows: [
        windowDetail({
          windowId: 4001,
          windowStatus: 'OCCUPIED',
          conflicts: [conflict({
            type: 'WINDOW_ALREADY_SCHEDULED',
            reason: '窗口[id=4001]在有效版本中已有活动任务[id=55]，必然落选',
            overlapStart: '2026-09-10T10:00:00Z',
            overlapEnd: '2026-09-10T10:10:00Z',
            stationId: 100
          })]
        }),
        windowDetail({
          windowId: 4002,
          conflicts: [conflict({
            type: 'STATION_OVERLAP',
            reason: '与地面站已有任务[id=56]时间重叠，同一地面站禁止重叠排程',
            overlapStart: '2026-09-10T10:06:00Z',
            overlapEnd: '2026-09-10T10:12:00Z',
            stationId: 100
          })]
        })
      ]
    })
    const wrapper = mountPanel(report)
    expect(wrapper.find('[data-test="conflict-tag-WINDOW_ALREADY_SCHEDULED"]').text())
      .toContain('窗口已安排')
    expect(wrapper.find('[data-test="conflict-row-4001"]').text()).toContain('任务[id=55]')
    expect(wrapper.find('[data-test="conflict-row-4002"]').text()).toContain('任务[id=56]')

    // 冲突定位按钮 → 聚合所有落选窗口的冲突
    await wrapper.find('[data-test="btn-locate"]').trigger('click')
    const located = wrapper.emitted('locate')
    expect(located).toBeTruthy()
    const all = located![0][0] as ConflictInfo[]
    expect(all).toHaveLength(2)
    expect(all.map(c => c.type)).toEqual(['WINDOW_ALREADY_SCHEDULED', 'STATION_OVERLAP'])
  })
})

describe('操作与跳转', () => {
  it('点击“依据预检生成草稿”发出 generate 事件', async () => {
    const report = makeReport()
    const wrapper = mountPanel(report)
    await wrapper.find('[data-test="btn-generate"]').trigger('click')
    const events = wrapper.emitted('generate')
    expect(events).toBeTruthy()
    expect((events![0][0] as PrecheckReport).id).toBe(12)
  })

  it('摘要跳转：甘特图 / 资源管理', async () => {
    const wrapper = mountPanel(makeReport())
    await wrapper.find('[data-test="btn-goto-gantt"]').trigger('click')
    await wrapper.find('[data-test="btn-goto-resources"]').trigger('click')
    const gotos = wrapper.emitted('goto')
    expect(gotos!.map(e => e[0])).toEqual(['gantt', 'resources'])
  })

  it('失败报告展示错误信息，不展示生成入口指标区', () => {
    const wrapper = mountPanel(makeReport({ status: 'FAILED', errorMessage: '预检分析失败：数据库连接中断' }))
    expect(wrapper.text()).toContain('数据库连接中断')
    expect(wrapper.find('[data-test="metrics"]').exists()).toBe(false)
  })
})

describe('幂等与历史报告', () => {
  it('命中同参数历史报告时展示提示标签', () => {
    const wrapper = mountPanel(makeReport({ cached: true }))
    expect(wrapper.find('[data-test="cached-tag"]').text()).toContain('未重复分析')
  })

  it('历史报告列表可重复查看，点击发出 view 事件', async () => {
    const current = makeReport()
    const history = makeReport({ id: 9, schedulableCount: 5, rejectedCount: 0, totalWindows: 5 })
    const wrapper = mountPanel(current, [current, history])
    const rows = wrapper.find('[data-test="history"]').findAll('.history-row')
    expect(rows).toHaveLength(2)
    await wrapper.find('[data-test="history-9"]').trigger('click')
    const views = wrapper.emitted('view')
    expect(views).toBeTruthy()
    expect((views![0][0] as PrecheckReport).id).toBe(9)
  })
})
