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
  /** 只看某个源导入的频道（按源分组用） */
  sourceId?: number
  page?: number
  size?: number
}

export async function listChannels(query: ChannelQuery = {}): Promise<ChannelPage> {
  const raw = await http.get<unknown>('channels', {
    group: query.group,
    q: query.q,
    source_id: query.sourceId,
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

/**
 * 设置台标。
 *
 * 不传 url 就抓频道当前那个外链；传了就用指定的。
 * 服务端把图下载到本地，并把频道 logo 改写成 /logo/{id}——
 * 之后所有终端都从服务端取图，不用再去连不稳定的外链。
 */
export function fetchLogo(channelId: number, url?: string): Promise<{ logo: string }> {
  return http.post<{ logo: string }>(`channels/${channelId}/logo`, url ? { url } : {})
}

/** 直接上传自己的图片（multipart）。 */
export async function uploadLogoFile(channelId: number, file: File): Promise<{ logo: string }> {
  const fd = new FormData()
  fd.append('file', file)
  const res = await fetch(`api/v1/channels/${channelId}/logo`, {
    method: 'POST',
    credentials: 'same-origin',
    body: fd,
  })
  const json = (await res.json()) as { code?: number; msg?: string; data?: { logo: string } }
  if (json.code !== 0 || !json.data) throw new Error(json.msg ?? '上传失败')
  return json.data
}

/** 清掉本地台标。 */
export function deleteLogo(channelId: number): Promise<unknown> {
  return http.del<unknown>(`channels/${channelId}/logo`)
}
