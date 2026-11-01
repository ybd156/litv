/**
 * 与 docs/design/01-API.md 对齐的数据类型。
 * 约定：服务端字段一律 snake_case，响应统一包一层 {code,msg,data}（由 http.ts 拆掉）。
 */

export interface ApiEnvelope<T> {
  code: number
  msg: string
  data: T
}

/** 当前登录用户（网关注入的 X-Trim-*） */
export interface Me {
  uid: string
  username: string
  is_admin: boolean
}

export type SourceType = 'm3u' | 'txt' | 'tvbox' | 'json'

export type SourceStatus = 'ok' | 'error' | 'never' | 'refreshing'

export interface Source {
  id: number
  name: string
  type: SourceType
  url: string
  enabled: boolean
  /** ok=最近一次拉取成功；error=失败；never=还没拉过 */
  status?: SourceStatus | string
  last_fetch_at?: string | null
  /** 最近一次失败原因（给用户看的人话） */
  error?: string | null
  /** 这条源解析出多少频道（可选字段，服务端有就展示） */
  channel_count?: number
  /** 这个源专属的 User-Agent（空 = 用全局默认） */
  user_agent?: string
}

export interface SourceInput {
  name: string
  type: SourceType
  url: string
  enabled: boolean
  /**
   * 这个源专属的 User-Agent。
   *
   * 很多源站校验 UA：不带就 403，或者返回空列表（表现为「导入 0 个频道」）。
   * 留空 = 用全局默认 UA。
   */
  user_agent?: string
}

/** 源预览里的一条（不落库） */
export interface PreviewItem {
  name: string
  url: string
  group?: string
  tvg_id?: string
  logo?: string
}

export interface PreviewResult {
  items: PreviewItem[]
  total?: number
  truncated?: boolean
}

export interface Channel {
  id: number
  number?: number
  name: string
  group: string
  logo?: string
  tvg_id?: string
  epg_id?: string | null
  enabled: boolean
  stream_count?: number
}

export interface ChannelInput {
  name: string
  group: string
  number?: number
  logo?: string
  tvg_id?: string
  epg_id?: string | null
  enabled: boolean
}

export interface ChannelPage {
  total: number
  items: Channel[]
}

export interface ChannelStream {
  id: number
  url: string
  source_id?: number
  source_name?: string
  /** 例如 1080P / 4Mbps，服务端探测有就展示 */
  quality?: string
  enabled?: boolean
  latency_ms?: number
}

export interface StreamInput {
  url: string
  source_id?: number
}

export type BatchAction = 'enable' | 'disable' | 'delete' | 'set_group'

export interface GroupStat {
  name: string
  count: number
}

export interface EpgSource {
  id: number
  name: string
  url: string
  enabled?: boolean
  last_refresh_at?: string | null
  channel_count?: number
  program_count?: number
  error?: string | null
}

export interface EpgSourceInput {
  name: string
  url: string
  enabled?: boolean
}

/** EPG 侧的一个频道（xmltv 里的 channel，id 通常就是 tvg-id） */
export interface EpgChannel {
  id: string
  name: string
  source?: string
  program_count?: number
}

export interface EpgProgram {
  start: string
  end: string
  title: string
  desc?: string
}

export type DeviceStatus = 'bound' | 'pending' | 'revoked'

export type DecodeMode = 'hard' | 'soft' | 'auto'
export type AspectMode = 'auto' | '16:9' | '4:3' | 'fill'

export interface DeviceConfig {
  /** 解码模式：硬解 / 软解 / 自动 */
  decode: DecodeMode
  /** 缓冲大小（毫秒） */
  buffer_ms: number
  /** 画面比例 */
  aspect: AspectMode
  /** 启动自动播放 */
  autoplay: boolean
}

export interface Device {
  id: number
  name: string
  platform: string
  model?: string
  online: boolean
  last_heartbeat_at?: string | null
  status?: DeviceStatus
  ip?: string
  app_version?: string
  config?: Partial<DeviceConfig> | null
}

export interface BindCode {
  code: string
  /** 剩余有效秒数，缺省按 300 秒倒计时 */
  expires_in?: number
  expires_at?: string
}

export interface SystemInfo {
  version: string
  arch?: string
  uptime_seconds?: number
  active_streams?: number
  /** 下面几个是概览页想要的统计，服务端有就展示，没有就显示 “—” */
  channels_total?: number
  devices_online?: number
  plays_today?: number
  sources_failed?: number
}

export interface SystemConfig {
  port: number
  max_concurrent: number
  transcode_enabled: boolean
  /** 可选：日志级别 / EPG 刷新时间 / 局域网访问 */
  log_level?: string
  epg_refresh_hour?: number
  allow_lan?: boolean
}
