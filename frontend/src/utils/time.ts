// 时间工具：后端统一 UTC。前端展示可在 UTC 与地面站本地时区之间切换。

const pad = (n: number) => String(n).padStart(2, '0')

/** Date -> "yyyy-MM-dd'T'HH:mm:ss'Z'"（UTC），用于提交后端。 */
export function toUtcIso(d: Date): string {
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}` +
    `T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}Z`
}

/** "yyyy-MM-dd" (UTC 日期)，用于日期筛选。 */
export function utcDate(d: Date = new Date()): string {
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`
}

export function parseTime(s?: string): Date {
  if (!s) return new Date(NaN)
  // 后端保证带 Z；兜底无 Z 输入按 UTC 解析
  return new Date(s.endsWith('Z') || s.includes('+') ? s : s.replace(' ', 'T') + 'Z')
}

/** HH:mm:ss，时区可选（默认 UTC）。 */
export function fmtTime(s: string | Date, tz: string = 'UTC', withSeconds = false): string {
  const d = typeof s === 'string' ? parseTime(s) : s
  try {
    return new Intl.DateTimeFormat('zh-CN', {
      timeZone: tz,
      hour: '2-digit',
      minute: '2-digit',
      second: withSeconds ? '2-digit' : undefined,
      hour12: false
    }).format(d)
  } catch {
    return new Intl.DateTimeFormat('zh-CN', {
      timeZone: 'UTC', hour: '2-digit', minute: '2-digit', hour12: false
    }).format(d)
  }
}

export function fmtDateTime(s?: string, tz: string = 'UTC'): string {
  if (!s) return '-'
  const d = parseTime(s)
  try {
    return new Intl.DateTimeFormat('zh-CN', {
      timeZone: tz,
      month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', hour12: false
    }).format(d)
  } catch {
    return s
  }
}

/** 计算某时刻在指定时区的 UTC 偏移（分钟）。 */
function tzOffsetMinutes(date: Date, tz: string): number {
  const dtf = new Intl.DateTimeFormat('en-US', {
    timeZone: tz, hour12: false,
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit'
  })
  const parts = dtf.formatToParts(date).reduce<Record<string, number>>((acc, p) => {
    if (p.type !== 'literal') acc[p.type] = Number(p.value)
    return acc
  }, {})
  const hour = parts.hour === 24 ? 0 : parts.hour
  const asUtc = Date.UTC(parts.year, parts.month - 1, parts.day, hour, parts.minute, parts.second)
  return (asUtc - date.getTime()) / 60000
}

/**
 * 日期边界转 UTC ISO：把 [dateStr 的当地 00:00, 次日当地 00:00)（按 tz 解释）换算为 UTC。
 * 例如 tz=Asia/Shanghai、dateStr=2026-09-10 → start=2026-09-09T16:00:00Z。
 */
export function dayRangeUtc(dateStr: string, tz: string = 'UTC'): { start: string; end: string } {
  if (tz === 'UTC') {
    return { start: `${dateStr}T00:00:00Z`, end: addDaysUtc(dateStr, 1) }
  }
  const localMidnightUtcGuess = new Date(`${dateStr}T00:00:00Z`)
  const offset = tzOffsetMinutes(localMidnightUtcGuess, tz) // 当地相对 UTC 的偏移
  const start = new Date(localMidnightUtcGuess.getTime() - offset * 60000)
  const end = new Date(start.getTime() + 86400_000)
  return { start: toUtcIso(start), end: toUtcIso(end) }
}

export function addDaysUtc(dateStr: string, days: number): string {
  const d = new Date(`${dateStr}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + days)
  return toUtcIso(d)
}

export const ACTION_LABELS: Record<string, string> = {
  GENERATE: '自动排程',
  ADD: '手工添加',
  ADJUST: '调整',
  CANCEL: '取消',
  REMOVE: '移除',
  PUBLISH: '发布',
  REVISE: '修订'
}
