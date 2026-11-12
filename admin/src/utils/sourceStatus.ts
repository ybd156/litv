/**
 * 源状态推导：**必须**和服务端 `GET /sources` 的视图逻辑一模一样。
 *
 * 服务端的顺序是：
 *   1. `last_error != ""`            → `error`
 *   2. 否则 `last_fetch_at == 0`     → `never`
 *   3. 否则                          → `ok`
 *
 * 为什么顺序不能换：一个「第一次拉取就失败」的源，`last_fetch_at` 是有值的
 * （MarkSourceFetched 会写时间），`last_error` 也非空。要是先判
 * `!last_fetch_at`，它就会被说成「还没拉过」——用户就不会去查失败原因了。
 *
 * 为什么还要在前端推导：`status` 是新服务端才有的键。连上没升级的 NAS 时
 * 响应里只有 `last_error` / `last_fetch_at`，只认 `status` 的话
 * 整个「拉取失败」信号都会消失（第一轮就是这个 bug：错误列永远「—」，
 * 失效源计数恒 0）。所以：有 status 用 status，没有就按同一顺序兜底。
 */
import type { Source } from '@/api/types'

export type DerivedSourceStatus = 'ok' | 'error' | 'never'

/** 失败原因：新服务端给 `error`，老服务端只有 `last_error`。两个都读。 */
export function sourceError(row: Source): string {
  return row.error ?? row.last_error ?? ''
}

export function derivedSourceStatus(row: Source): DerivedSourceStatus {
  const s = row.status
  if (s === 'ok' || s === 'error' || s === 'never') return s
  // 兜底：旧服务端没有 status
  if (sourceError(row).trim() !== '') return 'error'
  if (!row.last_fetch_at) return 'never'
  return 'ok'
}
