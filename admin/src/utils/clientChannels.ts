import type { ClientParsePolicy, PreviewItem } from '@/api/types'
import { channelKey, parseSourceText } from '@/utils/m3u'

/**
 * 客户端解析模式下的频道列表（浏览器里算）。
 *
 * 背景：边缘版把「解析」从 Worker 挪到了客户端——Worker 每次请求只有 10ms CPU，
 * 解析上千条 m3u 必然越界（1102），而且灌进 D1 还要花掉免费额度的大头。
 * 所以 `/admin/channels` 在 client 模式下只回 `{ client_parse, raw_paths, policy }`，
 * 列表得由管理台自己拉原文、自己解析。
 *
 * ★ 合并与过滤规则必须与电视端 `M3uClient.build()` 完全一致，否则
 *   「管理台看到的」和「电视上看到的」会变成两份不同的列表——用户只会
 *   以为同步坏了。
 */

/** 一行只读频道。**没有数字 id**：D1 里没有这行记录，不能用 Channel 那套类型 */
export interface ClientChannelRow {
  /** 稳定键（`id:<tvg-id>` 或 `name:<归一化名>`），同一台的多个来源共用一个 */
  key: string
  name: string
  group: string
  logo: string
  tvgId: string
  enabled: boolean
  /** 这个台一共有几条线路（多源同台会累加） */
  streams: number
  /** 来自哪个源；`sources/{id}/raw` 里解析出来的 */
  sourceId?: number
}

export interface ClientChannelResult {
  rows: ClientChannelRow[]
  /** 拉取/解析失败的源原文入口，界面要如实说明覆盖率 */
  failed: string[]
  /** 成功解析的源数量 */
  loaded: number
}

/** 从 `sources/{id}/raw` 里取出源 id；取不到就当这条没有来源归属 */
export function sourceIdOfRawPath(path: string): number | undefined {
  const m = /^sources\/(\d+)\/raw$/.exec(path.trim())
  return m ? Number(m[1]) : undefined
}

/**
 * 多份源文本 → 一份频道列表。
 *
 * 过滤只做 `groups_hidden` / `channels_hidden` 两件事：
 *   · 分组名精确命中（电视端就是 `it.group !in hiddenGroups`）
 *   · 频道显示名精确命中（不是 tvg-id）
 *
 * **不套用 `channel_overrides`**：电视端 `/tv/channel-src` 下发的 policy 里没有
 * 这张表，套了就会让管理台显示改名后的名字、电视上还是原名。两边必须一致，
 * 所以这里按原样显示，页面上的提示条会说明改名/停用在该模式下不生效。
 */
export function mergeClientChannels(
  texts: Array<{ rawPath: string; text: string }>,
  policy?: ClientParsePolicy,
): ClientChannelResult {
  const merged = new Map<string, ClientChannelRow>()
  const failed: string[] = []
  let loaded = 0

  for (const { rawPath, text } of texts) {
    const parsed = parseSourceText(text)
    // json / html / empty / unknown 不是错误，只是这个地址不是订阅文件；
    // 但一条都解析不出来时确实等于没拿到东西，如实计入 failed
    if (parsed.items.length === 0) {
      failed.push(rawPath)
      continue
    }
    loaded += 1

    const sourceId = sourceIdOfRawPath(rawPath)
    for (const item of parsed.items) {
      collect(merged, item, sourceId)
    }
  }

  const hiddenGroups = new Set(policy?.groups_hidden ?? [])
  const hiddenNames = new Set(policy?.channels_hidden ?? [])

  const rows: ClientChannelRow[] = []
  for (const row of merged.values()) {
    if (hiddenGroups.has(row.group)) continue
    if (hiddenNames.has(row.name)) continue
    rows.push(row)
  }

  return { rows, failed, loaded }
}

/** 同一台在多个源里出现：保留第一次的名字/分组/台标，线路数累加（与电视端一致） */
function collect(
  merged: Map<string, ClientChannelRow>,
  item: PreviewItem,
  sourceId?: number,
): void {
  const key = channelKey(item)
  const existing = merged.get(key)
  if (existing) {
    existing.streams += 1
    return
  }
  merged.set(key, {
    key,
    name: item.name,
    // 电视端空分组会落到「未分组」，两边显示同一套分组名
    group: (item.group || '').trim() || '未分组',
    logo: item.logo || '',
    tvgId: item.tvg_id || '',
    enabled: true,
    streams: 1,
    sourceId,
  })
}

/** 侧栏分组统计（按频道数降序，与分组接口给的形式一致） */
export function clientGroupStats(rows: ClientChannelRow[]): Array<{ name: string; count: number }> {
  const m = new Map<string, number>()
  for (const r of rows) m.set(r.group, (m.get(r.group) ?? 0) + 1)
  return [...m.entries()]
    .map(([name, count]) => ({ name, count }))
    .sort((a, b) => b.count - a.count || a.name.localeCompare(b.name, 'zh-Hans-CN'))
}
