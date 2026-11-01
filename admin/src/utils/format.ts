/** 时间/文本格式化：界面上一律说人话，不直接甩 ISO 字符串 */

function toDate(v?: string | number | Date | null): Date | null {
  if (!v) return null
  const d = v instanceof Date ? v : new Date(v)
  return Number.isNaN(d.getTime()) ? null : d
}

const pad = (n: number): string => String(n).padStart(2, '0')

export function fmtDateTime(v?: string | number | Date | null): string {
  const d = toDate(v)
  if (!d) return '—'
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(
    d.getMinutes(),
  )}`
}

export function fmtClock(v?: string | number | Date | null): string {
  const d = toDate(v)
  if (!d) return '—'
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 「多久没更新了」这种口径，比 last_fetch_at 好懂 */
export function timeAgo(v?: string | number | Date | null): string {
  const d = toDate(v)
  if (!d) return '还没拉过'
  const diff = Date.now() - d.getTime()
  if (diff < 0) return '刚刚'
  const min = Math.floor(diff / 60_000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour} 小时前`
  const day = Math.floor(hour / 24)
  if (day < 30) return `${day} 天前`
  const month = Math.floor(day / 30)
  if (month < 12) return `${month} 个月前`
  return `${Math.floor(month / 12)} 年前`
}

export function fmtDuration(seconds?: number | null): string {
  if (typeof seconds !== 'number' || seconds < 0 || !Number.isFinite(seconds)) return '—'
  const s = Math.floor(seconds)
  const day = Math.floor(s / 86_400)
  const hour = Math.floor((s % 86_400) / 3_600)
  const min = Math.floor((s % 3_600) / 60)
  if (day > 0) return `${day} 天 ${hour} 小时`
  if (hour > 0) return `${hour} 小时 ${min} 分`
  if (min > 0) return `${min} 分 ${s % 60} 秒`
  return `${s} 秒`
}

export function fmtCount(n?: number | null): string {
  if (typeof n !== 'number' || !Number.isFinite(n)) return '—'
  if (n >= 10_000) return `${(n / 10_000).toFixed(1)} 万`
  return String(n)
}

/** 6 位绑定码拆成 3+3，弹窗上更好读 */
export function splitBindCode(code: string): string[] {
  return code.replace(/\s/g, '').split('')
}

export function truncate(text: string, len = 48): string {
  if (text.length <= len) return text
  return `${text.slice(0, len)}…`
}
