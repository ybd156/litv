import { http } from '../http'
import { asArray } from '../normalize'
import type {
  BatchAction,
  Channel,
  ChannelInput,
  ChannelPage,
  ChannelStream,
  GroupStat,
  StreamInput,
} from '../types'

export interface ChannelQuery {
  group?: string
  q?: string
  page?: number
  size?: number
}

export async function listChannels(query: ChannelQuery = {}): Promise<ChannelPage> {
  const raw = await http.get<unknown>('channels', {
    group: query.group,
    q: query.q,
    page: query.page ?? 1,
    size: query.size ?? 20,
  })
  if (Array.isArray(raw)) {
    return { total: raw.length, items: raw as Channel[] }
  }
  const obj = (raw ?? {}) as Record<string, unknown>
  const items = asArray<Channel>(raw)
  return {
    total: typeof obj.total === 'number' ? obj.total : items.length,
    items,
  }
}

export async function listGroups(): Promise<GroupStat[]> {
  const raw = await http.get<unknown>('groups')
  return asArray<GroupStat>(raw).map((g) => ({
    name: g.name ?? '未分组',
    count: typeof g.count === 'number' ? g.count : 0,
  }))
}

export function createChannel(input: ChannelInput): Promise<Channel> {
  return http.post<Channel>('channels', input)
}

export function updateChannel(id: number, input: Partial<ChannelInput>): Promise<Channel> {
  return http.put<Channel>(`channels/${id}`, input)
}

export function deleteChannel(id: number): Promise<unknown> {
  return http.del<unknown>(`channels/${id}`)
}

/** 拖拽排序：把当前页的新顺序整体提交 */
export function reorderChannels(ids: number[]): Promise<unknown> {
  return http.post<unknown>('channels/reorder', { ids })
}

export function batchChannels(ids: number[], action: BatchAction, value?: string): Promise<unknown> {
  return http.post<unknown>('channels/batch', { ids, action, value })
}

export async function listStreams(channelId: number): Promise<ChannelStream[]> {
  const raw = await http.get<unknown>(`channels/${channelId}/streams`)
  return asArray<ChannelStream>(raw)
}

export function addStream(channelId: number, input: StreamInput): Promise<ChannelStream> {
  return http.post<ChannelStream>(`channels/${channelId}/streams`, input)
}

export function deleteStream(channelId: number, streamId: number): Promise<unknown> {
  return http.del<unknown>(`channels/${channelId}/streams/${streamId}`)
}
