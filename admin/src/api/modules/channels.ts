import { http } from '../http'
import { asArray } from '../normalize'
import type {
  BatchAction,
  Channel,
  ChannelInput,
  ChannelPage,
  ChannelPatch,
  ChannelStream,
  ClientParsePolicy,
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

/** 把服务端返回归一成 ChannelPage（分页与「按窗口翻全量」两条路径共用）。 */
function normalizeChannelPage(raw: unknown): ChannelPage {
  if (Array.isArray(raw)) {
    return { total: raw.length, items: raw as Channel[] }
  }
  const obj = (raw ?? {}) as Record<string, unknown>
  const items = asArray<Channel>(raw)
  return {
    total: typeof obj.total === 'number' ? obj.total : items.length,
    items,
    // 客户端解析模式下服务端会把整份列表一次性给全（D1 里没有表，没法分页）
    clientParse: obj.client_parse === true,
    rawPaths: Array.isArray(obj.raw_paths) ? obj.raw_paths.map(String) : undefined,
    policy: parseClientPolicy(obj.policy),
  }
}

/**
 * 频道分页列表。
 *
 * silent：频道页 / EPG 映射页都有 `error` 态，概览页用 `Promise.allSettled` 兜底，
 * 再弹全局红条只会把页内那个带「重试」的提示盖住。
 */
export async function listChannels(query: ChannelQuery = {}): Promise<ChannelPage> {
  const raw = await http.get<unknown>(
    'channels',
    {
      group: query.group,
      q: query.q,
      source_id: query.sourceId,
      page: query.page ?? 1,
      size: query.size ?? 20,
    },
    { silent: true },
  )
  return normalizeChannelPage(raw)
}

export interface ChannelWindowQuery {
  limit?: number
  offset?: number
  enabled?: boolean
  group?: string
  q?: string
  sourceId?: number
}

/**
 * 按 `limit` / `offset` 取一页。
 *
 * 预览页要把整个列表翻完，用的是 limit/offset；而 `listChannels` 走 page/size。
 * 两套口径混用会在 total 与实际条数不一致时错位（比如客户端解析模式一次性给全），
 * 所以单独命名，别让调用方去猜哪个参数生效。
 */
export async function listChannelWindow(query: ChannelWindowQuery = {}): Promise<ChannelPage> {
  const raw = await http.get<unknown>(
    'channels',
    {
      limit: query.limit,
      offset: query.offset,
      enabled: query.enabled,
      group: query.group,
      q: query.q,
      source_id: query.sourceId,
    },
    { silent: true },
  )
  return normalizeChannelPage(raw)
}

/**
 * 解析 `policy`。
 *
 * 服务端可能给 `null`、也可能字段缺失（老版本 Worker），这里一律归一成
 * 一份「空规则」，调用方不用到处判空，也不会因为一个坏字段把整页搞崩。
 */
function parseClientPolicy(raw: unknown): ClientParsePolicy | undefined {
  if (!raw || typeof raw !== 'object') return undefined
  const obj = raw as Record<string, unknown>
  const strList = (v: unknown): string[] =>
    Array.isArray(v) ? v.map((x) => String(x)).filter((x) => x !== '') : []
  const overrides = Array.isArray(obj.overrides)
    ? (obj.overrides as Array<Record<string, unknown>>)
        .filter((o) => o && typeof o.stable_key === 'string')
        .map((o) => ({
          stable_key: String(o.stable_key),
          name: typeof o.name === 'string' ? o.name : null,
          group_name: typeof o.group_name === 'string' ? o.group_name : null,
          enabled: typeof o.enabled === 'number' ? o.enabled : null,
        }))
    : []
  return {
    groups_hidden: strList(obj.groups_hidden),
    channels_hidden: strList(obj.channels_hidden),
    overrides,
  }
}

/** 分组统计（侧栏用）：失败不阻塞主表，调用方自己兜底，所以 silent。 */
export async function listGroups(): Promise<GroupStat[]> {
  const raw = await http.get<unknown>('groups', undefined, { silent: true })
  return asArray<GroupStat>(raw).map((g) => ({
    name: g.name ?? '未分组',
    count: typeof g.count === 'number' ? g.count : 0,
  }))
}

/**
 * 新建频道。
 *
 * ★ 服务端只回 `{id}`（`createChannel` → `ok(w, {"id": id})`），不是 Channel。
 */
export function createChannel(input: ChannelInput): Promise<{ id: number }> {
  return http.post<{ id: number }>('channels', input, { silent: true })
}

/**
 * 部分更新频道：只提交要改的字段。
 *
 * ★ 为什么必须是「部分」：服务端曾经把 `PUT /channels/{id}` 当全量覆盖处理，
 * 前端只发 `{enabled:false}` 时，它没提到的 name/group/logo/tvg_id/number
 * 全被写成空值——点一下「停用」，频道名就没了。
 * 现在两个方向都收窄：服务端按「给了哪个字段改哪个」，前端也只说「我要改什么」。
 *
 * ★ `epg_id` 不在这里，走 `setChannelEpg()`。
 */
export function patchChannel(id: number, patch: ChannelPatch): Promise<unknown> {
  return http.put<unknown>(`channels/${id}`, patch, { silent: true })
}

/**
 * 设置 / 解除频道的 EPG 映射。
 *
 * ★ 必须走这个专用端点，不能塞进 `PUT /channels/{id}`：
 * 服务端 `mapChannelEPG` 只认 `{epg_id}`；而 `patchChannel` 的请求体里**没有**
 * `epg_id` 字段，且解码开了 `DisallowUnknownFields` —— 塞进去就是
 * 400 `参数错误: json: unknown field "epg_id"`。
 * 传空字符串表示解除映射（`store.SetChannelEpgID(id, "")`）。
 */
export function setChannelEpg(id: number, epgId: string | null): Promise<unknown> {
  return http.put<unknown>(`channels/${id}/epg`, { epg_id: epgId ?? '' }, { silent: true })
}

export function deleteChannel(id: number): Promise<unknown> {
  return http.del<unknown>(`channels/${id}`, undefined, { silent: true })
}

/** 拖拽排序：把当前页的新顺序整体提交 */
export function reorderChannels(ids: number[]): Promise<unknown> {
  return http.post<unknown>('channels/reorder', { ids }, { silent: true })
}

export function batchChannels(ids: number[], action: BatchAction, value?: string): Promise<unknown> {
  return http.post<unknown>('channels/batch', { ids, action, value }, { silent: true })
}

/** 频道的线路列表（展开行里用）：面板自己有错误态，silent。 */
export async function listStreams(channelId: number): Promise<ChannelStream[]> {
  const raw = await http.get<unknown>(`channels/${channelId}/streams`, undefined, { silent: true })
  return asArray<ChannelStream>(raw)
}

/** 加一条线路。服务端回 `{id}`，不是 Stream。 */
export function addStream(channelId: number, input: StreamInput): Promise<{ id: number }> {
  return http.post<{ id: number }>(`channels/${channelId}/streams`, input, { silent: true })
}

export function deleteStream(channelId: number, streamId: number): Promise<unknown> {
  return http.del<unknown>(`channels/${channelId}/streams/${streamId}`, undefined, { silent: true })
}

/**
 * 设置台标。
 *
 * 不传 url 就抓频道当前那个外链；传了就用指定的。
 * 服务端把图下载到本地，并把频道 logo 改写成 /logo/{id}——
 * 之后所有终端都从服务端取图，不用再去连不稳定的外链。
 */
export function fetchLogo(channelId: number, url?: string): Promise<{ logo: string }> {
  return http.post<{ logo: string }>(`channels/${channelId}/logo`, url ? { url } : {}, { silent: true })
}

/**
 * 直接上传自己的图片（multipart）。
 *
 * ★ 必须走 `http.post`，不要自己 `fetch`：自己拼 URL 会漏掉三件东西——
 *   1) 应急登录的 `Authorization: Bearer`（`http.ts` 统一注入），结果是 401；
 *   2) 信封 `{code,msg,data}` 的解析与 `2001` 未认证跳转；
 *   3) 网关把请求兜成 index.html 时的兜底处理（`res.json()` 会直接抛 SyntaxError）。
 * `http.ts` 已经专门处理了 FormData：不设 Content-Type，交给浏览器带 boundary。
 */
export function uploadLogoFile(channelId: number, file: File): Promise<{ logo: string }> {
  const fd = new FormData()
  fd.append('file', file)
  return http.post<{ logo: string }>(`channels/${channelId}/logo`, fd, { silent: true })
}

/** 清掉本地台标。 */
export function deleteLogo(channelId: number): Promise<unknown> {
  return http.del<unknown>(`channels/${channelId}/logo`, undefined, { silent: true })
}
