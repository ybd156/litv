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

/**
 * 源的**拉取结果**状态：封闭三值。
 *
 * ★ 不要往里加 `'refreshing'`。曾经有过，结果就是「刷新中」被混进拉取结果里，
 * 和 `ok/error/never` 永久打架（一个字段表达两件事）。刷新中只由
 * `Source.refreshing: boolean` 表达，两者互不替代。
 */
export type SourceStatus = 'ok' | 'error' | 'never'

export interface Source {
  id: number
  name: string
  type: SourceType
  url: string
  enabled: boolean
  /**
   * ok=最近一次拉取成功；error=失败；never=还没拉过。
   *
   * ★ 服务端 `GET /sources` 现在给这个字段（视图层推导），但**老服务端没有**。
   * 界面不能只认它：`SourcesView` 里用 `last_error` / `last_fetch_at` 兜底推导，
   * 否则一次服务端降级就会让「拉取失败」重新变成「正常」。
   */
  status?: SourceStatus | string
  /** 服务端是 Unix **秒**（Go int64）；这里兼容历史字符串形态 */
  last_fetch_at?: string | number | null
  /**
   * 服务端 store.Source 的原始字段名（永远存在）。
   * `error` 是视图层给的同义键——两者都读，谁在就用谁。
   */
  last_error?: string | null
  /** 与 last_error 同义（新视图两者都给）。最近一次失败原因，给用户看的人话 */
  error?: string | null
  /** 这条源解析出多少频道（可选字段，服务端有就展示） */
  channel_count?: number
  /** 这个源专属的 User-Agent（空 = 用全局默认） */
  user_agent?: string
  /** 创建时间（Unix 秒） */
  created_at?: number
  /**
   * 这个源**正在刷新**（服务端异步任务在跑）。
   *
   * ★ 刷新状态**只认这个布尔键**，不要去看 `status`：
   * `status` 的取值域被锁死为 `ok|error|never`（拉取结果），
   * 把「刷新中」塞进 `status` 会和那三个值永久打架。
   * 老服务端没有这个键 → `undefined`，当作不在跑。
   */
  refreshing?: boolean
  /**
   * 上一次「对齐上游」刷新的结果计数。
   *
   * 只有任务跑完、`refreshing` 变 false 之后才有值。**202 的响应体里没有它**
   * （那里面只有 `status:"running"`），所以必须轮询 `GET /sources` 从行上读。
   */
  last_result?: SourceRefreshCounts | null
  /**
   * 上一轮「上游给了多少条」——和 `channel_count`（这个源在**库内**有多少频道）
   * 是两个数，别混：上游 3000 条也可能只入库 2800 个（去重/过滤）。
   */
  parsed_count?: number
}

/**
 * 一次「对齐上游」刷新的结果计数。
 *
 * 全部可选：老服务端只回 `parsed` / `inserted`，新服务端多回
 * `updated`（可变字段变了）/ `missing`（上游已移除，本地标记下线）/
 * `unchanged`（完全没变）。界面按「有就显示」处理，不假设齐全。
 *
 * 注意 `missing` 在频道表里是 `channels.src_missing` 列（见 `Channel.missing`），
 * 这里的 `missing` 是「这一轮下线了几个台」的计数。
 */
export interface SourceRefreshCounts {
  parsed?: number
  inserted?: number
  updated?: number
  missing?: number
  unchanged?: number
}

/**
 * `POST /sources/{id}/refresh` 的返回。
 *
 * 两种形态都要能处理：
 *   - 异步（新）：HTTP 202 + `{status:"running"}`（可能带 `task_id`），
 *     真正的计数稍后从 `GET /sources` 的行上读；
 *   - 同步（老/小源）：直接回 `{parsed, inserted}` 等计数。
 * `isRefreshRunning()` 负责区分。
 */
export interface SourceRefreshResponse extends SourceRefreshCounts {
  status?: 'running' | 'done' | 'ok' | string
  refreshing?: boolean
  task_id?: string | number
  error?: string | null
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
  /** 来源源 id（0 = 手工建的）。服务端一直有这个字段，之前类型里漏了，页面只能强转 */
  source_id?: number
  /** 排序值（服务端给，界面不直接编辑，拖拽走 reorder 接口） */
  sort?: number
  /**
   * 上游已移除：最近一轮源解析里已经没有这个台了。
   *
   * ★ 服务端用独立列 `channels.src_missing` 记，**不复用 `enabled=0`**——
   * 复用会和「用户手动停用」永久混在一起，界面再也分不清两种状态。
   * 所以这里是三态而不是两态：
   *   enabled=false + missing=false → 用户自己停的（别去打扰他）
   *   missing=true                 → 上游没了（线路被清空，用户要知道为什么少台）
   * 服务端还没带这个键时是 `undefined`，界面退回原来的两态。
   */
  missing?: boolean
}

/**
 * 创建频道。
 *
 * ★ **不含 `epg_id`**：服务端 `POST /channels` 的请求体里没有这个字段，而 `decode()`
 * 开了 `DisallowUnknownFields`，多传一个 `epg_id` 就是 400「参数错误: json: unknown field」。
 * EPG 映射统一走 `PUT /channels/{id}/epg`（见 `channels.ts` 的 `setChannelEpg`）。
 */
export interface ChannelInput {
  name: string
  group: string
  number?: number
  logo?: string
  tvg_id?: string
  enabled: boolean
}

/**
 * 更新频道（部分更新）。
 *
 * 服务端按「给了哪个字段就改哪个」处理，所以这里全部可选；
 * 同样**不含 `epg_id`**（那是 `setChannelEpg` 的活）。
 */
export type ChannelPatch = Partial<{
  name: string
  group: string
  number: number
  logo: string
  tvg_id: string
  enabled: boolean
}>

export interface ChannelPage {
  total: number
  items: Channel[]
}

export interface ChannelStream {
  id: number
  url: string
  source_id?: number
  /**
   * ⚠️ 只有 mock 会填这个字段；真服务端 `GET /channels/{id}/streams` 返回的是
   * store.Stream（id/url/quality/priority/headers_json/health/latency_ms），没有来源名。
   * 所以面板必须用 `source_id` 兜底，不能只靠它。
   */
  source_name?: string
  /** 例如 1080P / 4Mbps，服务端探测有就展示 */
  quality?: string
  enabled?: boolean
  latency_ms?: number
  /** 1 健康 / 0 失效 / -1 未探测 */
  health?: number
  priority?: number
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

/**
 * 屏蔽清单（**边缘降级部署专有**）。
 *
 * NAS 服务端没有这个概念：那边的「隐藏」是逐个频道改 overrides。
 * 边缘版按分组整类屏蔽，因为订阅源里几百个台、而且每周都会加新的同类频道。
 */
export interface BlockedInfo {
  /** 已屏蔽的分组名 */
  groups: string[]
  /** 已屏蔽的频道名（精确匹配） */
  channels: string[]
  /** 从源里读到的分类，带「会下发的频道数」；已屏蔽的也在里面（count 可能为 0） */
  candidates: BlockedCategory[]
}

export interface BlockedCategory {
  name: string
  count: number
  blocked: boolean
}

/**
 * 边缘部署的设置自省（**边缘专有**，NAS 服务端没有这个接口）。
 *
 * 场景：BIND_CODE 是 GitHub Secret，部署完就沉在那儿；
 * 过几个月要加一台电视时没人记得那 6 位数字。
 */
export interface EdgeSettings {
  /** 绑定码：默认打码（12•••56），`reveal=1` 时才是明文 */
  bind_code: string
  bind_code_masked: string
  /** 没配 = 电视根本绑不上，界面要直接指出来 */
  bind_code_set: boolean
  /** 管理台口令是否已配置（来自 ADMIN_PASSWORD，与绑定码彻底分开） */
  admin_password_set: boolean
  /** 自定义入口路径（空 = 根路径即入口） */
  admin_path: string
  /** 给人看的入口地址，如 `/litv-9f3a` 或 `/` */
  admin_entry: string
}
