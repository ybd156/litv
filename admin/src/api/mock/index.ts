import type { HttpMethod, RequestOptions } from '../http'
import { ApiError, messageForCode } from '../errors'
import type {
  BindCode,
  Channel,
  ChannelInput,
  ChannelStream,
  Device,
  DeviceConfig,
  EpgSource,
  GroupStat,
  Me,
  PreviewResult,
  Source,
  SourceInput,
  SystemInfo,
} from '../types'
import { createDb, epgProgramsFor, previewItems, type MockDb } from './data'

/**
 * 开发模式假数据层。
 * 触发条件：仅允许开发服务器或显式 mock 构建启用。
 * 开发环境可用 ?mock=1（?mock=unauth 可预览未认证页）临时切换状态。
 * 生产构建忽略地址栏参数，避免用户通过 URL 强制进入假数据模式。
 * 数据存在内存里，刷新页面即还原，方便反复试各种状态。
 */

const db: MockDb = createDb()

function params(): URLSearchParams {
  return new URLSearchParams(location.search.replace(/^\?/, ''))
}

export function mockEnabled(): boolean {
  const mockBuild = typeof __MOCK_MODE__ !== 'undefined' && __MOCK_MODE__
  if (!import.meta.env.DEV && !mockBuild) return false

  // 只有开发服务器允许用地址栏切换；显式 mock 构建始终使用假数据。
  if (import.meta.env.DEV) {
    const q = params().get('mock')
    if (q === '1' || q === 'true' || q === 'unauth') return true
    if (q === '0' || q === 'false') return false
  }
  if (mockBuild) return true

  const flag = import.meta.env.VITE_MOCK
  if (flag === '1' || flag === 'true') return true
  if (flag === '0' || flag === 'false') return false
  return typeof __MOCK_MODE__ !== 'undefined' && __MOCK_MODE__
}

function mockUnauthorized(): boolean {
  return params().get('mock') === 'unauth'
}

function bad(msg: string, code = 1001): never {
  throw new ApiError(code, messageForCode(code, msg), 400)
}

function notFound(msg: string): never {
  throw new ApiError(3001, messageForCode(3001, msg), 404)
}

function nextId(): number {
  db.nextId += 1
  return db.nextId
}

function isoNow(): string {
  return new Date().toISOString()
}

/**
 * mock 的「异步刷新任务」。
 *
 * 真服务端已经改成：POST 立刻回 `{status:"running"}`，快慢取决于源大小；
 * 结果（新增/更新/下线/未变）过一会儿出现在 `GET /sources` 那一行的
 * `last_result` 上。mock 必须复刻这条**两段式**路径，否则页面里新写的
 * 「轮询 + 行级 refreshing」逻辑在本地一次都不会被执行，等于没测。
 *
 * 这里用「开始时间 + 固定 1.2 秒后完成」模拟；每次读列表推进一次状态。
 */
const MOCK_REFRESH_MS = 1200
const mockRefreshStarted = new Map<number, number>()

function settleMockRefresh(): void {
  const now = Date.now()
  for (const s of db.sources) {
    const started = mockRefreshStarted.get(s.id)
    if (s.refreshing !== true || started === undefined) continue
    if (now - started < MOCK_REFRESH_MS) continue
    mockRefreshStarted.delete(s.id)
    s.refreshing = false
    s.last_fetch_at = isoNow()
    if (s.status === 'error') {
      // 失败时两个键一起更新：界面对 error / last_error 是「谁在就用谁」，
      // mock 只改一个就会造出真服务端不可能出现的组合。
      s.last_error = '拉取失败：HTTP 403 Forbidden，源站可能换了地址'
      s.error = s.last_error
      s.parsed_count = 0
      s.last_result = { parsed: 0, inserted: 0, updated: 0, missing: 0, unchanged: 0 }
    } else {
      const parsed = 18 + ((s.id * 7) % 40)
      // 造一组「确实有变化」的计数：用户要看到的就是「这次刷新到底动了什么」
      const inserted = s.id % 3 === 0 ? 2 : 0
      const updated = s.id % 3 === 1 ? 5 : 0
      const missing = s.id % 3 === 2 ? 1 : 0
      s.status = 'ok'
      s.last_error = null
      s.error = null
      s.parsed_count = parsed
      s.channel_count = parsed - missing
      s.last_result = {
        parsed,
        inserted,
        updated,
        missing,
        unchanged: parsed - inserted - updated - missing,
      }
    }
  }
}

interface Route {
  method: HttpMethod
  re: RegExp
  handle: (m: RegExpMatchArray, opts: RequestOptions) => unknown
}

function groups(): GroupStat[] {
  const map = new Map<string, number>()
  for (const c of db.channels) {
    const key = c.group || '未分组'
    map.set(key, (map.get(key) ?? 0) + 1)
  }
  return [...map.entries()]
    .map(([name, count]) => ({ name, count }))
    .sort((a, b) => a.name.localeCompare(b.name, 'zh-Hans-CN'))
}

function findChannel(id: number): Channel {
  const ch = db.channels.find((c) => c.id === id)
  if (!ch) notFound(`频道 ${id}`)
  return ch
}

const routes: Route[] = [
  // ---- 会话 ----
  {
    method: 'GET',
    re: /^system\/me$/,
    handle: () => {
      if (mockUnauthorized()) {
        throw new ApiError(2001, messageForCode(2001), 401)
      }
      const me: Me = { uid: '1000', username: 'admin', is_admin: true }
      return me
    },
  },

  // ---- 源 ----
  {
    method: 'GET',
    re: /^sources$/,
    // 每次读列表前先推进一下「假的刷新任务」：真服务端也是靠轮询这里拿到结果，
    // mock 必须走同一条路径，否则页面里那段轮询逻辑在 mock 下永远不被执行到。
    handle: () => {
      settleMockRefresh()
      return db.sources
    },
  },
  {
    method: 'POST',
    re: /^sources$/,
    handle: (_m, opts) => {
      const body = (opts.body ?? {}) as SourceInput
      if (!body.name || !body.url) bad('名称和地址都要填')
      const item: Source = {
        id: nextId(),
        name: body.name,
        type: body.type ?? 'm3u',
        url: body.url,
        enabled: body.enabled ?? true,
        // 三件套齐全：status / last_error / error（与真服务端视图对齐）
        status: 'never',
        last_fetch_at: null,
        last_error: null,
        error: null,
        channel_count: 0,
      }
      db.sources.unshift(item)
      // ★ 真服务端只回 {id}（createSource → ok(w, {"id": id})）。
      // mock 跟着回同样的形状，否则前端一旦去读 .name 就会在真机上 undefined。
      return { id: item.id }
    },
  },
  {
    method: 'PUT',
    re: /^sources\/(\d+)$/,
    handle: (m, opts) => {
      const id = Number(m[1])
      const item = db.sources.find((s) => s.id === id)
      if (!item) notFound(`源 ${id}`)
      const body = (opts.body ?? {}) as Partial<SourceInput>
      Object.assign(item, body)
      return item
    },
  },
  {
    method: 'DELETE',
    re: /^sources\/(\d+)$/,
    handle: (m) => {
      const id = Number(m[1])
      db.sources = db.sources.filter((s) => s.id !== id)
      return { ok: true }
    },
  },
  {
    method: 'POST',
    re: /^sources\/(\d+)\/refresh$/,
    handle: (m) => {
      const id = Number(m[1])
      const item = db.sources.find((s) => s.id === id)
      if (!item) notFound(`源 ${id}`)
      // ★ 真服务端已经改成异步（A2）：立刻回 202 + {status:"running"}，
      // 计数稍后出现在 GET /sources 那一行的 last_result 上。
      // mock 原来直接返回整个 Source 对象 —— 与真服务端的差异会让
      // 「页面在等同步结果」这种 bug 在本地永远暴露不出来（第一轮 /sources 事故同款）。
      if (item.refreshing === true) {
        // 单飞：已经在跑就再说一次「在跑」，不重复起任务
        return { status: 'running' }
      }
      item.refreshing = true
      mockRefreshStarted.set(id, Date.now())
      return { status: 'running' }
    },
  },
  {
    method: 'GET',
    re: /^sources\/(\d+)\/preview$/,
    handle: (m) => {
      const id = Number(m[1])
      const item = db.sources.find((s) => s.id === id)
      if (!item) notFound(`源 ${id}`)
      const items = previewItems()
      const result: PreviewResult = { items, total: items.length + 2, truncated: true }
      return result
    },
  },

  // ---- 分组 / 频道 ----
  { method: 'GET', re: /^groups$/, handle: () => groups() },
  {
    method: 'GET',
    re: /^channels$/,
    handle: (_m, opts) => {
      const q = opts.query ?? {}
      const group = typeof q.group === 'string' ? q.group : ''
      const kw = typeof q.q === 'string' ? q.q.trim().toLowerCase() : ''
      const page = Number(q.page ?? 1) || 1
      const size = Number(q.size ?? 20) || 20
      let list = [...db.channels]
      if (group) list = list.filter((c) => c.group === group)
      if (kw) {
        list = list.filter(
          (c) =>
            c.name.toLowerCase().includes(kw) ||
            (c.tvg_id ?? '').toLowerCase().includes(kw) ||
            String(c.number ?? '').includes(kw),
        )
      }
      const total = list.length
      const start = (page - 1) * size
      return { total, items: list.slice(start, start + size) }
    },
  },
  {
    method: 'POST',
    re: /^channels$/,
    handle: (_m, opts) => {
      const body = (opts.body ?? {}) as ChannelInput
      if (!body.name) bad('频道名不能为空')
      const item: Channel = {
        id: nextId(),
        number: body.number ?? db.channels.length + 1,
        name: body.name,
        group: body.group || '未分组',
        logo: body.logo ?? '',
        tvg_id: body.tvg_id ?? '',
        // 创建时没有 EPG 映射：服务端 `POST /channels` 的请求体里没有 epg_id，
        // 传了会被 DisallowUnknownFields 拒掉。映射只能之后走 /channels/{id}/epg。
        epg_id: null,
        enabled: body.enabled ?? true,
        stream_count: 0,
      }
      db.channels.push(item)
      db.streams[item.id] = []
      // 真服务端只回 {id}
      return { id: item.id }
    },
  },
  {
    method: 'PUT',
    re: /^channels\/(\d+)$/,
    handle: (m, opts) => {
      const item = findChannel(Number(m[1]))
      // 部分更新：只覆盖请求里真的出现的键（与修好后的服务端语义一致）。
      const patch = { ...((opts.body ?? {}) as Record<string, unknown>) }
      // ★ epg_id 明确不经本接口：服务端请求体里没有这个字段，
      // 塞进来会 400。这里也拒绝，避免 mock 下「能改」而真机不能改。
      if ('epg_id' in patch) bad('EPG 映射请用 PUT /channels/{id}/epg', 1001)
      for (const [k, v] of Object.entries(patch)) {
        if (v === undefined) continue
        ;(item as unknown as Record<string, unknown>)[k] = v
      }
      return { updated: true }
    },
  },
  {
    method: 'PUT',
    re: /^channels\/(\d+)\/epg$/,
    handle: (m, opts) => {
      const item = findChannel(Number(m[1]))
      const body = (opts.body ?? {}) as { epg_id?: string }
      // 空串 = 解除映射（服务端 SetChannelEpgID(id, "")）
      item.epg_id = body.epg_id ? body.epg_id : null
      return { mapped: true }
    },
  },
  {
    method: 'DELETE',
    re: /^channels\/(\d+)$/,
    handle: (m) => {
      const id = Number(m[1])
      db.channels = db.channels.filter((c) => c.id !== id)
      delete db.streams[id]
      return { ok: true }
    },
  },
  {
    method: 'POST',
    re: /^channels\/reorder$/,
    handle: (_m, opts) => {
      const ids = ((opts.body ?? {}) as { ids?: number[] }).ids ?? []
      const idSet = new Set(ids)
      const ordered = ids
        .map((id) => db.channels.find((c) => c.id === id))
        .filter((c): c is Channel => Boolean(c))
      let k = 0
      db.channels = db.channels.map((c) => (idSet.has(c.id) ? ordered[k++] : c))
      db.channels.forEach((c, i) => {
        c.number = i + 1
      })
      return { ok: true }
    },
  },
  {
    method: 'POST',
    re: /^channels\/batch$/,
    handle: (_m, opts) => {
      const body = (opts.body ?? {}) as { ids?: number[]; action?: string; value?: string }
      const ids = body.ids ?? []
      if (!ids.length) bad('先选中频道')
      const idSet = new Set(ids)
      switch (body.action) {
        case 'enable':
          db.channels.forEach((c) => {
            if (idSet.has(c.id)) c.enabled = true
          })
          break
        case 'disable':
          db.channels.forEach((c) => {
            if (idSet.has(c.id)) c.enabled = false
          })
          break
        case 'delete':
          db.channels = db.channels.filter((c) => !idSet.has(c.id))
          ids.forEach((id) => delete db.streams[id])
          break
        case 'set_group': {
          const g = (body.value ?? '').trim() || '未分组'
          db.channels.forEach((c) => {
            if (idSet.has(c.id)) c.group = g
          })
          break
        }
        default:
          bad('不支持的批量操作')
      }
      return { ok: true, affected: ids.length }
    },
  },
  {
    method: 'GET',
    re: /^channels\/(\d+)\/streams$/,
    handle: (m) => db.streams[Number(m[1])] ?? [],
  },
  {
    method: 'POST',
    re: /^channels\/(\d+)\/streams$/,
    handle: (m, opts) => {
      const id = Number(m[1])
      const ch = findChannel(id)
      const body = (opts.body ?? {}) as { url?: string; source_id?: number }
      if (!body.url) bad('线路地址不能为空')
      const list = (db.streams[id] ??= [])
      const stream: ChannelStream = {
        id: nextId(),
        url: body.url,
        source_id: body.source_id,
        source_name: body.source_id ? `源 #${body.source_id}` : '手动添加',
        quality: '未探测',
        enabled: true,
        latency_ms: undefined,
      }
      list.push(stream)
      ch.stream_count = list.length
      return stream
    },
  },
  {
    method: 'DELETE',
    re: /^channels\/(\d+)\/streams\/(\d+)$/,
    handle: (m) => {
      const id = Number(m[1])
      const sid = Number(m[2])
      const ch = findChannel(id)
      db.streams[id] = (db.streams[id] ?? []).filter((s) => s.id !== sid)
      ch.stream_count = db.streams[id].length
      return { ok: true }
    },
  },

  // ---- EPG ----
  { method: 'GET', re: /^epg\/sources$/, handle: () => db.epgSources },
  {
    method: 'POST',
    re: /^epg\/sources$/,
    handle: (_m, opts) => {
      const body = (opts.body ?? {}) as { name?: string; url?: string; enabled?: boolean }
      if (!body.name || !body.url) bad('名称和地址都要填')
      const item: EpgSource = {
        id: nextId(),
        name: body.name,
        url: body.url,
        enabled: body.enabled ?? true,
        last_refresh_at: null,
        channel_count: 0,
        program_count: 0,
        error: null,
      }
      db.epgSources.push(item)
      return item
    },
  },
  {
    method: 'DELETE',
    re: /^epg\/sources\/(\d+)$/,
    handle: (m) => {
      const id = Number(m[1])
      db.epgSources = db.epgSources.filter((s) => s.id !== id)
      return { ok: true }
    },
  },
  {
    method: 'POST',
    re: /^epg\/refresh$/,
    handle: () => {
      db.epgSources.forEach((s) => {
        s.last_refresh_at = isoNow()
        s.error = null
      })
      return { ok: true, refreshed: db.epgSources.length }
    },
  },
  // 契约缺口：01-API.md 没写 EPG 侧频道列表接口，这里给个建议实现 GET /epg/channels
  { method: 'GET', re: /^epg\/channels$/, handle: () => db.epgChannels },
  {
    method: 'GET',
    re: /^epg\/(\d+)$/,
    handle: (m, opts) => {
      const id = Number(m[1])
      const day = Number((opts.query ?? {}).day ?? 0) || 0
      return epgProgramsFor(id, day)
    },
  },

  // ---- 设备 ----
  { method: 'GET', re: /^devices$/, handle: () => db.devices },
  { method: 'GET', re: /^device\/pending$/, handle: () => db.devices.filter((d) => d.status === 'pending') },
  {
    method: 'POST',
    re: /^devices\/bindcode$/,
    handle: () => {
      const code = String(Math.floor(100000 + Math.random() * 900000))
      const out: BindCode = { code, expires_in: 300 }
      return out
    },
  },
  {
    method: 'POST',
    re: /^devices\/(\d+)\/approve$/,
    handle: (m) => {
      const d = db.devices.find((x) => x.id === Number(m[1]))
      if (!d) notFound('设备')
      d.status = 'bound'
      return d
    },
  },
  {
    method: 'POST',
    re: /^devices\/(\d+)\/reject$/,
    handle: (m) => {
      const id = Number(m[1])
      db.devices = db.devices.filter((x) => x.id !== id)
      return { ok: true }
    },
  },
  {
    method: 'DELETE',
    re: /^devices\/(\d+)$/,
    handle: (m) => {
      const id = Number(m[1])
      db.devices = db.devices.filter((x) => x.id !== id)
      return { ok: true }
    },
  },
  {
    method: 'PUT',
    re: /^devices\/(\d+)\/config$/,
    handle: (m, opts) => {
      const d: Device | undefined = db.devices.find((x) => x.id === Number(m[1]))
      if (!d) notFound('设备')
      d.config = { ...(d.config ?? {}), ...((opts.body ?? {}) as DeviceConfig) }
      return d
    },
  },

  // ---- 系统 ----
  {
    method: 'GET',
    re: /^system\/info$/,
    handle: () => {
      const info: SystemInfo = {
        version: '0.9.3-dev',
        arch: 'linux/amd64',
        uptime_seconds: 37 * 3600 + 12 * 60,
        active_streams: 2,
        channels_total: db.channels.length,
        devices_online: db.devices.filter((d) => d.online).length,
        plays_today: db.playsToday,
        sources_failed: db.sources.filter((s) => s.status === 'error').length,
      }
      return info
    },
  },
  { method: 'GET', re: /^system\/config$/, handle: () => db.config },
  {
    method: 'PUT',
    re: /^system\/config$/,
    handle: (_m, opts) => {
      Object.assign(db.config, (opts.body ?? {}) as Record<string, unknown>)
      return db.config
    },
  },
  {
    method: 'GET',
    re: /^system\/logs$/,
    handle: (_m, opts) => {
      const lines = Number((opts.query ?? {}).lines ?? 200) || 200
      return { lines: db.logs.slice(-lines) }
    },
  },
]

export async function mockRequest<T>(path: string, method: HttpMethod, opts: RequestOptions): Promise<T> {
  const clean = path.replace(/^\/+/, '').replace(/\?.*$/, '')
  const route = routes.find((r) => r.method === method && r.re.test(clean))
  await new Promise((r) => setTimeout(r, 120 + Math.random() * 160))
  if (!route) {
    throw new ApiError(0, `Mock 未实现：${method} ${clean}`, 404)
  }
  const m = clean.match(route.re)
  if (!m) throw new ApiError(0, `Mock 路由异常：${clean}`, 500)
  return route.handle(m, opts) as T
}
