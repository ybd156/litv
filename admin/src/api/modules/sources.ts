import { http } from '../http'
import { asArray } from '../normalize'
import type { PreviewItem, PreviewResult, Source, SourceInput } from '../types'

export async function listSources(): Promise<Source[]> {
  const raw = await http.get<unknown>('sources')
  return asArray<Source>(raw)
}

export function createSource(input: SourceInput): Promise<Source> {
  return http.post<Source>('sources', input)
}

export function updateSource(id: number, input: Partial<SourceInput>): Promise<Source> {
  return http.put<Source>(`sources/${id}`, input)
}

export function deleteSource(id: number): Promise<unknown> {
  return http.del<unknown>(`sources/${id}`)
}

export function refreshSource(id: number): Promise<Source> {
  return http.post<Source>(`sources/${id}/refresh`)
}

/** 预览：拉取但不入库，只给前 50 条，用来肉眼排查脏数据 */
export async function previewSource(id: number): Promise<PreviewResult> {
  const raw = await http.get<unknown>(`sources/${id}/preview`)
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
