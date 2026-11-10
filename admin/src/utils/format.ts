/** 时间/文本格式化：界面上一律说人话，不直接甩 ISO 字符串 */

/**
 * 把服务端的时间值转成 Date。
 *
 * ⚠️ 关键：服务端所有时间字段都是 Unix **秒**（Go 的 time.Now().Unix()），
 * 而 JS 的 new Date(数字) 按**毫秒**解释。
 * 不换算的话，new Date(1793600000) 得到的是 1970 年，
 * 界面上就会显示「57 年前」——设备最后心跳、源上次拉取、EPG 上次刷新全中招。
 *
 * 判定方式：现代毫秒时间戳都是 1e12 量级（2001 年后），
 * 而秒时间戳到 33658 年才会到 1e12。所以 < 1e12 一律按秒处理。
 */
function toDate(v?: string | number | Date | null): Date | null {
  if (v === 0 || v === '0' || v === null || v === undefined || v === '') return null
  if (v instanceof Date) return Number.isNaN(v.getTime()) ? null : v

  if (typeof v === 'number') {
    const ms = v < 1e12 ? v * 1000 : v
    const d = new Date(ms)
    return Number.isNaN(d.getTime()) ? null : d
  }

  // 纯数字字符串也按同样的规则（服务端可能把 int64 序列化成字符串）
  if (/^\d+$/.test(v)) {
    const n = Number(v)
    if (!n) return null
    const d = new Date(n < 1e12 ? n * 1000 : n)
    return Number.isNaN(d.getTime()) ? null : d
  }

  const d = new Date(v)
  return Number.isNaN(d.getTime()) ? null : d
}

const pad = (n: number): string => String(n).padStart(2, '0')

/**
 * 把服务端时间值压成毫秒数，用于**排序/比较**（不是展示）。
 *
 * ★ 存在的理由：不经过这里就会出现
 * `new Date(b.time).getTime() - new Date(a.time).getTime()` 这种写法——
 * 服务端给的是**秒**，`new Date(秒)` 得到的是 1970 年。同一批数据下排序
 * 恰好还对（单调），一旦混进 ISO 字符串（mock 就是 ISO）就彻底乱。
 * 凡是拿时间做比较的地方，统一走这里，别再自己 new Date。
 */
export function toMillis(v?: string | number | Date | null): number {
  const d = toDate(v)
  return d ? d.getTime() : 0
}

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
