import { http } from '../http'
import { asArray } from '../normalize'
import type { EpgChannel, EpgProgram, EpgSource, EpgSourceInput } from '../types'

export async function listEpgSources(): Promise<EpgSource[]> {
  const raw = await http.get<unknown>('epg/sources')
  return asArray<EpgSource>(raw)
}

export function createEpgSource(input: EpgSourceInput): Promise<EpgSource> {
  return http.post<EpgSource>('epg/sources', input)
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
  return asArray<EpgProgram>(raw)
}
