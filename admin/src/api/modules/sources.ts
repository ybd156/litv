import { http } from '../http'
import { asArray } from '../normalize'
import type { PreviewItem, PreviewResult, Source, SourceInput, SourceRefreshResponse } from '../types'

/**
 * 源列表。
 *
 * silent：源管理页与概览页都有自己的错误态（`StateBlock` / `Promise.allSettled`），
 * 不需要再叠一条全局红条。
 */
export async function listSources(): Promise<Source[]> {
  const raw = await http.get<unknown>('sources', undefined, { silent: true })
  return asArray<Source>(raw)
}

/**
 * 新建源。
 *
 * ★ 服务端只回 `{id}`（`handlers_admin.go` 的 createSource → `ok(w, {"id": id})`），
 * 不是整个 Source。之前这里标成 `Promise<Source>` 是类型在说谎——调用方一旦
 * 去读 `.channel_count` 就会拿到 undefined。
 */
export function createSource(input: SourceInput): Promise<{ id: number }> {
  return http.post<{ id: number }>('sources', input, { silent: true })
}

/** 更新源：服务端回 `{updated:true}`，不回实体。 */
export function updateSource(id: number, input: Partial<SourceInput>): Promise<unknown> {
  return http.put<unknown>(`sources/${id}`, input, { silent: true })
}

export function deleteSource(id: number): Promise<unknown> {
  return http.del<unknown>(`sources/${id}`, undefined, { silent: true })
}

/**
 * 立即拉取并解析（对齐上游）。
 *
 * ★ 契约变更（第二批 A2）：服务端改成异步任务——大源会立刻回
 * `{status:"running"}`（HTTP 202），真正的结果要过一会儿从
 * `GET /sources` 的行上读（`refreshing` 变 false 后才有五键计数）。
 * 小源/老服务端仍然直接回计数。
 * 判断要不要轮询统一用 `utils/sourceRefresh.ts` 的 `isRefreshRunning()`，
 * 页面不要自己猜 `status` 的取值。
 */
export function refreshSource(id: number): Promise<SourceRefreshResponse> {
  return http.post<SourceRefreshResponse>(`sources/${id}/refresh`, undefined, { silent: true })
}

/** 预览：拉取但不入库，只给前 50 条，用来肉眼排查脏数据 */
export async function previewSource(id: number): Promise<PreviewResult> {
  const raw = await http.get<unknown>(`sources/${id}/preview`, undefined, { silent: true })
  if (Array.isArray(raw)) {
    return { items: raw as PreviewItem[], total: raw.length, truncated: raw.length >= 50 }
  }
  const obj = (raw ?? {}) as Record<string, unknown>
  const items = asArray<PreviewItem>(raw)
  return {
    items,
    total: typeof obj.total === 'number' ? obj.total : items.length,
    truncated: obj.truncated === true || items.length >= 50,
  }
}
