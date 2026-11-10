/**
 * 源刷新的「读法」工具。
 *
 * 背景（第二批缺陷 #4「上游更新，里面没有自动更新，反而卡死」）：
 * 刷新从「同步跑完再返回」改成了「立刻回 202 + 稍后轮询」，返回体也从
 * `{parsed, inserted}` 扩成五个计数。管理台必须同时对付三种情况：
 *
 *   1. 新服务端 + 大源：POST 立刻回 `{status:"running"}`，计数要等 `GET /sources`
 *      里那一行的 `refreshing` 变 false、`last_result` 出现才能读；
 *   2. 新服务端 + 小源：POST 直接回完整计数（`status` 不是 running）；
 *   3. 老服务端：只有 `{parsed, inserted}`。
 *
 * 所以这里所有读取都容忍缺字段——**绝不假设某个键一定存在**。
 * 缺字段时界面显示「已刷新」，而不是显示一个错的 0。
 */
import type { Source, SourceRefreshCounts, SourceRefreshResponse } from '@/api/types'

function num(v: unknown): number | undefined {
  return typeof v === 'number' && Number.isFinite(v) ? v : undefined
}

/** 从计数对象里挑出存在的维度，缺的就不填（不补 0，避免把「没有这个维度」说成 0）。 */
export function countsOf(v: SourceRefreshCounts | null | undefined): SourceRefreshCounts {
  if (!v) return {}
  const raw = v as Record<string, unknown>
  const out: SourceRefreshCounts = {}
  const parsed = num(raw.parsed)
  const inserted = num(raw.inserted)
  const updated = num(raw.updated)
  // 契约里叫 missing（上线数）；早期草稿/别名用过 disabled，两个都认，
  // 免得某个中间态服务端把「下线数」藏起来不显示。
  const missing = num(raw.missing) ?? num(raw.disabled)
  const unchanged = num(raw.unchanged)
  if (parsed !== undefined) out.parsed = parsed
  if (inserted !== undefined) out.inserted = inserted
  if (updated !== undefined) out.updated = updated
  if (missing !== undefined) out.missing = missing
  if (unchanged !== undefined) out.unchanged = unchanged
  return out
}

export function hasCounts(c: SourceRefreshCounts): boolean {
  return Object.keys(c).length > 0
}

/**
 * POST 之后是否还要轮询：只有明确说「在跑」才算。
 *
 * 注意这里看的是 **POST 响应体的 `status`**（异步化了就是 `"running"`）。
 * 列表行上的刷新状态**不看** `status`，只看行级 `refreshing: boolean`
 * ——`status` 的取值域被锁死为 `ok|error|never`，混进第四个值会和它打架。
 */
export function isRefreshRunning(resp: SourceRefreshResponse | null | undefined): boolean {
  if (!resp) return false
  if (resp.refreshing === true) return true
  if (resp.status === 'running') return true
  // 有 task_id 但一个计数都没给，说明任务刚起、结果还没出来
  if (resp.task_id !== undefined && resp.task_id !== null && resp.task_id !== '') {
    return !hasCounts(countsOf(resp))
  }
  return false
}

/** 从 `GET /sources` 的一行里读上一次刷新的计数（只在 `refreshing` 变 false 后才有）。 */
export function sourceCountsOf(row: Source | null | undefined): SourceRefreshCounts {
  return countsOf(row?.last_result)
}

/**
 * 人类可读的刷新结果。
 *
 * 只报**用户能感知的**三个量：新增 / 更新 / 下线。
 * `parsed`（上游给了几条）与 `unchanged`（没变的）对用户没意义，只进 tooltip。
 */
export function refreshSummary(c: SourceRefreshCounts | null | undefined): string {
  const n = countsOf(c)
  const parts: string[] = []
  if (n.inserted !== undefined) parts.push(`新增 ${n.inserted}`)
  if (n.updated !== undefined) parts.push(`更新 ${n.updated}`)
  if (n.missing !== undefined) parts.push(`下线 ${n.missing}`)
  if (parts.length === 0) {
    if (n.parsed !== undefined) return `已解析 ${n.parsed} 条`
    return ''
  }
  return parts.join(' · ')
}

/** tooltip 用的完整口径。 */
export function refreshDetail(c: SourceRefreshCounts | null | undefined): string {
  const n = countsOf(c)
  const parts: string[] = []
  if (n.parsed !== undefined) parts.push(`上游解析 ${n.parsed} 条`)
  if (n.inserted !== undefined) parts.push(`新增 ${n.inserted}`)
  if (n.updated !== undefined) parts.push(`更新 ${n.updated}`)
  if (n.missing !== undefined) parts.push(`下线 ${n.missing}`)
  if (n.unchanged !== undefined) parts.push(`未变 ${n.unchanged}`)
  return parts.join(' · ')
}
