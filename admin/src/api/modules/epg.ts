import { http } from '../http'
import { asArray } from '../normalize'
import type {
  EpgAuditEntry,
  EpgAutomapReport,
  EpgChannel,
  EpgProgram,
  EpgMappingConflict,
  EpgProgramHit,
  EpgUnmatchedChannel,
  EpgRefreshResult,
  EpgSource,
  EpgSourceInput,
  EpgStats,
} from '../types'

/**
 * EPG 源列表。
 *
 * silent：面板自己有 `StateBlock` 错误块。
 */
export async function listEpgSources(): Promise<EpgSource[]> {
  const raw = await http.get<unknown>('epg/sources', undefined, { silent: true })
  return asArray<EpgSource>(raw)
}

/**
 * 新建 EPG 源。
 *
 * ★ 服务端只回 `{id}`；之前标成 `Promise<EpgSource>` 是类型在说谎。
 * silent：调用方（EpgSourcePanel）自己 toast。
 */
export function createEpgSource(input: EpgSourceInput): Promise<{ id: number }> {
  return http.post<{ id: number }>('epg/sources', input, { silent: true })
}

/** 编辑 EPG 源（A4）。服务端回 `{saved:true}`。 */
export function updateEpgSource(id: number, input: EpgSourceInput): Promise<unknown> {
  return http.put<unknown>(`epg/sources/${id}`, input, { silent: true })
}

/** 刷新单个源（A2），返回该源的刷新明细。 */
export function refreshOneEpg(id: number): Promise<EpgRefreshResult> {
  return http.post<EpgRefreshResult>(`epg/sources/${id}/refresh`, undefined, { silent: true })
}

/** 导入本地 XMLTV 文件（A8）。 */
export function importEpg(file: File, name?: string): Promise<unknown> {
  const fd = new FormData()
  fd.append('file', file)
  if (name) fd.append('name', name)
  return http.post<unknown>('epg/import', fd, { silent: true })
}

export function deleteEpgSource(id: number): Promise<unknown> {
  return http.del<unknown>(`epg/sources/${id}`, undefined, { silent: true })
}

export function refreshEpg(): Promise<unknown> {
  return http.post<unknown>('epg/refresh', undefined, { silent: true })
}

/**
 * EPG 侧的频道清单。
 * ★ 契约缺口：01-API.md 只定义了 /epg/sources、/epg/refresh、/epg/{channel_id}，
 * 没有"EPG 里都有哪些频道"的接口，做映射界面必须要它。这里按建议的 GET /epg/channels 对接，
 * 服务端未实现时会拿到 404，界面降级为「用频道表里出现过的 epg_id 反推」。
 */
export async function listEpgChannels(): Promise<EpgChannel[]> {
  // silent 是**必须**的：这条接口在「服务端没实现」时会 404，而映射页拿 404
  // 当正常降级信号用。非 silent 的话每次进页面都会先闪一条红条再说「已降级」。
  const raw = await http.get<unknown>('epg/channels', undefined, { silent: true })
  return asArray<EpgChannel>(raw)
}

export async function listPrograms(channelId: number, day = 0): Promise<EpgProgram[]> {
  const raw = await http.get<unknown>(`epg/${channelId}`, { day }, { silent: true })
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

/** EPG 总览统计（D1）。silent：拉不到就整条统计栏不显示，不该弹红条。 */
export function fetchEpgStats(): Promise<EpgStats> {
  return http.get<EpgStats>('epg/stats', undefined, { silent: true })
}

/** 清理过期节目（D2）。silent：调用方自己 toast 结果/错误。 */
export function cleanupEpg(keepDays?: number): Promise<{ deleted: number; keep_days: number; note: string }> {
  return http.post<{ deleted: number; keep_days: number; note: string }>(
    'epg/cleanup',
    keepDays === undefined ? {} : { keep_days: keepDays },
    { silent: true },
  )
}

/** EPG 操作审计（D3）。 */
export async function fetchEpgLog(limit = 100): Promise<EpgAuditEntry[]> {
  const raw = await http.get<unknown>('epg/refresh-log', { limit }, { silent: true })
  return asArray<EpgAuditEntry>(raw)
}

/** 按节目名搜索（D5）。silent：搜索弹窗自己有错误处理。 */
export async function searchEpg(q: string, limit = 50): Promise<EpgProgramHit[]> {
  const raw = await http.get<unknown>('epg/search', { q, limit }, { silent: true })
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
    const obj = raw as { items?: unknown }
    if (Array.isArray(obj.items)) return obj.items as EpgProgramHit[]
    return []
  }
  return asArray<EpgProgramHit>(raw)
}

/** 按频道名自动匹配（B1/B2）。silent：调用方自己报「扫描/匹配/未匹配」。 */
export function automapEpg(): Promise<EpgAutomapReport> {
  return http.post<EpgAutomapReport>('epg/automap', undefined, { silent: true })
}

/** 按台号自动匹配（B4）。 */
export function mapEpgByNumber(): Promise<EpgAutomapReport> {
  return http.post<EpgAutomapReport>('epg/map/by-number', undefined, { silent: true })
}

/** 未匹配频道 + 映射冲突（B5/B6）。silent：这是诊断信息，缺了不该打断用户。 */
export async function fetchEpgMapping(): Promise<{
  unmatched: EpgUnmatchedChannel[]
  conflicts: EpgMappingConflict[]
}> {
  const raw = await http.get<unknown>('epg/mapping', undefined, { silent: true })
  const obj = (raw ?? {}) as { unmatched?: unknown; conflicts?: unknown }
  return {
    unmatched: Array.isArray(obj.unmatched) ? (obj.unmatched as EpgUnmatchedChannel[]) : [],
    conflicts: Array.isArray(obj.conflicts) ? (obj.conflicts as EpgMappingConflict[]) : [],
  }
}

/** 批量映射（B3）。epgId 传空表示解除映射。 */
export function mapEpgBatch(channelIds: number[], epgId: string): Promise<{ updated: number }> {
  return http.post<{ updated: number }>(
    'epg/map/batch',
    { channel_ids: channelIds, epg_id: epgId },
    { silent: true },
  )
}
