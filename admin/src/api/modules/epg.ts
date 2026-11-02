import { http } from '../http'
import { asArray } from '../normalize'
import type {
  EpgAuditEntry,
  EpgChannel,
  EpgProgram,
  EpgProgramHit,
  EpgRefreshResult,
  EpgSource,
  EpgSourceInput,
  EpgStats,
} from '../types'

export async function listEpgSources(): Promise<EpgSource[]> {
  const raw = await http.get<unknown>('epg/sources')
  return asArray<EpgSource>(raw)
}

export function createEpgSource(input: EpgSourceInput): Promise<EpgSource> {
  return http.post<EpgSource>('epg/sources', input)
}

/** 编辑 EPG 源（A4）。 */
export function updateEpgSource(id: number, input: EpgSourceInput): Promise<unknown> {
  return http.put<unknown>(`epg/sources/${id}`, input)
}

/** 刷新单个源（A2），返回该源的刷新明细。 */
export function refreshOneEpg(id: number): Promise<EpgRefreshResult> {
  return http.post<EpgRefreshResult>(`epg/sources/${id}/refresh`)
}

/** 导入本地 XMLTV 文件（A8）。 */
export function importEpg(file: File, name?: string): Promise<unknown> {
  const fd = new FormData()
  fd.append('file', file)
  if (name) fd.append('name', name)
  return http.post<unknown>('epg/import', fd)
}

export function deleteEpgSource(id: number): Promise<unknown> {
  return http.del<unknown>(`epg/sources/${id}`)
}

export function refreshEpg(): Promise<unknown> {
  return http.post<unknown>('epg/refresh')
}

/**
 * EPG 侧的频道清单。
 * ★ 契约缺口：01-API.md 只定义了 /epg/sources、/epg/refresh、/epg/{channel_id}，
 * 没有"EPG 里都有哪些频道"的接口，做映射界面必须要它。这里按建议的 GET /epg/channels 对接，
 * 服务端未实现时会拿到 404，界面降级为「用频道表里出现过的 epg_id 反推」。
 */
export async function listEpgChannels(): Promise<EpgChannel[]> {
  const raw = await http.get<unknown>('epg/channels')
  return asArray<EpgChannel>(raw)
}

export async function listPrograms(channelId: number, day = 0): Promise<EpgProgram[]> {
  const raw = await http.get<unknown>(`epg/${channelId}`, { day })
  // ⚠️ 服务端 data 是**对象** {channel, programs}，不是数组
  //（见 server/internal/api/handlers_client.go 的 tvEPG）。
  // asArray 只认 items/list/rows/lines/data，遇到 programs 返回空数组，
  // 于是节目单永远显示不出来。
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
    const obj = raw as { programs?: unknown }
    if (Array.isArray(obj.programs)) return obj.programs as EpgProgram[]
    // 未关联 EPG 时服务端给 programs: []，这里同样返回空数组
    return []
  }
  // 兼容旧形状（裸数组）
  return asArray<EpgProgram>(raw)
}

/** EPG 总览统计（D1）。 */
export function fetchEpgStats(): Promise<EpgStats> {
  return http.get<EpgStats>('epg/stats')
}

/** 清理过期节目（D2）。 */
export function cleanupEpg(keepDays?: number): Promise<{ deleted: number; keep_days: number; note: string }> {
  return http.post<{ deleted: number; keep_days: number; note: string }>('epg/cleanup',
    keepDays === undefined ? {} : { keep_days: keepDays })
}

/** EPG 操作审计（D3）。 */
export async function fetchEpgLog(limit = 100): Promise<EpgAuditEntry[]> {
  const raw = await http.get<unknown>('epg/refresh-log', { limit })
  return asArray<EpgAuditEntry>(raw)
}

/** 按节目名搜索（D5）。 */
export async function searchEpg(q: string, limit = 50): Promise<EpgProgramHit[]> {
  const raw = await http.get<unknown>('epg/search', { q, limit })
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
    const obj = raw as { items?: unknown }
    if (Array.isArray(obj.items)) return obj.items as EpgProgramHit[]
    return []
  }
  return asArray<EpgProgramHit>(raw)
}
