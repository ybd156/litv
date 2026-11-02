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
  /** 上次**成功**同步的时间（Unix 秒）。失败时不会变。 */
  /** 越大越优先；同一 xmltv_id 多源时高优先级覆盖低优先级 */
  priority?: number
  user_agent?: string
  referer?: string
  /** url = 远程拉取；file = 本地上传 */
  kind?: string
  last_refresh_at?: string | null
  /** 上次**尝试**同步的时间（Unix 秒），不论成败。 */
  last_attempt_at?: string | null
  channel_count?: number
  program_count?: number
  error?: string | null
}

export interface EpgSourceInput {
  name: string
  url: string
  enabled?: boolean
  /** 越大越优先。同一 xmltv_id 出现在多个源时，高优先级覆盖低优先级。 */
  priority?: number
  /** 防盗链：拉取时带的 UA / Referer。 */
  user_agent?: string
  referer?: string
  /** url = 远程拉取；file = 本地上传的 XMLTV */
  kind?: string
}

/** EPG 总览统计（D1）。 */
export interface EpgStats {
  sources: number
  enabled_sources: number
  channels: number
  programs: number
  earliest_start: number
  latest_end: number
  covered_days: number
  last_sync: number
  db_bytes: number
  keep_days: number
}

/** 节目搜索结果（D5）。 */
export interface EpgProgramHit {
  epg_channel_id: number
  xmltv_id: string
  channel_name: string
  title: string
  start_at: number
  end_at: number
  category: string
}

/** EPG 操作审计（D3）。 */
export interface EpgAuditEntry {
  id: number
  action: string
  target: string
  detail: string
  created_at: number
}

/** 未匹配 EPG 的频道（B5），带最佳候选建议（B2）。 */
export interface EpgUnmatchedChannel {
  id: number
  number: number
  name: string
  group: string
  best_guess: string
  best_score: number
}

/** 多个频道映射到同一个 EPG 频道（B6）。 */
export interface EpgMappingConflict {
  epg_id: string
  channels: string[]
}

/** 自动匹配报告（B1）。 */
export interface EpgAutomapReport {
  scanned: number
  matched: number
  unmatched: EpgUnmatchedChannel[]
  conflicts: EpgMappingConflict[]
}

/** 单个源的刷新明细（A3）。 */
export interface EpgRefreshResult {
  id: number
  name?: string
  ok: boolean
  channels: number
  programs: number
  elapsed_ms: number
  error?: string
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
  /**
   * 保存后服务端回传：哪些字段已写入配置文件但还需重启才生效。
   * 只出现在 PUT /system/config 的响应里，GET 不会带。
   */
  restart_needed?: string[]
}
