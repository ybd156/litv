import { adminToken, apiUrl, http } from '../http'
import { asArray } from '../normalize'
import type { PreviewItem, PreviewResult, Source, SourceInput, SourceRefreshResponse } from '../types'
import { decodeSourceBytes, parseSourceText } from '@/utils/m3u'

/** 预览最多展示多少条（与原有卡片文案一致） */
const PREVIEW_LIMIT = 50

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
 * ★ 服务端只回 `{id}`（`handlers_admin_source.go` 的 createSource → `ok(w, {"id": id})`），
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

/**
 * 预览：拉取但不入库，只给前 50 条，用来肉眼排查脏数据。
 *
 * 两条路：
 *   · NAS 服务端（Go）：`/preview` 已经把解析结果算好，直接用。
 *   · 边缘部署（Worker）：`/preview` 只回 `{ raw_path }`——解析在浏览器里做。
 *     Worker 每次请求只有 10ms CPU，解析上千条 m3u 必然越界（1102）。
 */
export async function previewSource(id: number): Promise<PreviewResult> {
  const raw = await http.get<unknown>(`sources/${id}/preview`, undefined, { silent: true })

  const obj = (raw ?? {}) as Record<string, unknown>
  if (typeof obj.raw_path === 'string' && obj.raw_path) {
    const text = await fetchSourceText(obj.raw_path)
    const parsed = parseSourceText(text)
    return {
      items: parsed.items.slice(0, PREVIEW_LIMIT),
      total: parsed.items.length,
      truncated: parsed.items.length > PREVIEW_LIMIT,
      format: parsed.format,
    }
  }

  if (Array.isArray(raw)) {
    return { items: raw as PreviewItem[], total: raw.length, truncated: raw.length >= PREVIEW_LIMIT }
  }
  const items = asArray<PreviewItem>(raw)
  return {
    items,
    total: typeof obj.total === 'number' ? obj.total : items.length,
    truncated: obj.truncated === true || items.length >= PREVIEW_LIMIT,
    format: typeof obj.format === 'string' ? obj.format : undefined,
  }
}

/**
 * 拉一个源的原始字节（边缘部署的 `/sources/{id}/raw`）。
 *
 * 走裸 fetch 而不是 `http.get`：后端原样返回文本/字节，不是 `{code,msg,data}` 信封。
 * 鉴权照旧带 Bearer（边缘管理台登录后存在 localStorage）。
 */
/**
 * 拉一个源的原始字节（边缘部署的 `/sources/{id}/raw`）。
 *
 * 走裸 fetch 而不是 `http.get`：后端原样返回文本/字节，不是 `{code,msg,data}` 信封。
 * 鉴权照旧带 Bearer（边缘管理台登录后存在 localStorage）。
 *
 * 频道页在客户端解析模式下也用它：`/admin/channels` 只给 `raw_paths`，
 * 由浏览器逐个拉原文再解析（Worker 每次请求只有 10ms CPU，解析不了）。
 */
export async function fetchSourceText(rawPath: string): Promise<string> {
  const headers: Record<string, string> = { Accept: '*/*' }
  const tk = adminToken()
  if (tk) headers.Authorization = `Bearer ${tk}`

  let res: Response
  try {
    res = await fetch(apiUrl(rawPath), { method: 'GET', credentials: 'same-origin', headers })
  } catch (e) {
    throw new Error(`拉取源失败：${e instanceof Error ? e.message : String(e)}`)
  }

  if (!res.ok) {
    // 401 已经由 http 层统一处理；这里只把状态码变成人话
    throw new Error(
      res.status === 401
        ? '登录态已过期，请重新登录后再预览'
        : `拉取源失败（HTTP ${res.status}）`,
    )
  }

  return decodeSourceBytes(await res.arrayBuffer())
}
