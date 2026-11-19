import type { PreviewItem } from '@/api/types'

/**
 * 源文件解析（浏览器端）。
 *
 * 为什么在前端做：边缘版 Worker 每次请求只有 10ms CPU，解析上千条 m3u 必然越界
 * （错误 1102）——管理台「预览解析结果」长期显示「共 0 条」就是这个原因。
 * 现在 Worker 只把源字节透传出来（`/sources/{id}/raw`），解析在这里做：
 * 浏览器有充足的 CPU 与内存，而且不花 D1 的读写额度。
 *
 * **规则必须与 `edge/scripts/build.mjs` 的 parseM3U / parseTXT / detectFormat 对齐**，
 * 否则「预览看到的」和「CI 真正入库的」会是两份不同的结果。
 */

/** 与 server/internal/parse 的 looksLikeURL、edge/scripts/build.mjs 的 PROTOCOLS 一致 */
const PROTOCOLS = [
  'http://',
  'https://',
  'rtsp://',
  'rtmp://',
  'rtp://',
  'udp://',
  'rtp2://',
  'p3p://',
  'p2p://',
  'mitv://',
  'igmp://',
  'file://',
]

export type SourceFormat = 'm3u' | 'txt' | 'json' | 'html' | 'empty' | 'unknown'

function looksLikeURL(s: string): boolean {
  const t = s.trim().toLowerCase()
  return PROTOCOLS.some((p) => t.startsWith(p))
}

/** #EXTINF 的属性段和频道名之间用逗号分隔，但属性值里也可能有逗号 */
function splitExtinf(line: string): [string, string] {
  let inQuote = false
  for (let i = 0; i < line.length; i++) {
    const c = line[i]
    if (c === '"') inQuote = !inQuote
    else if (c === ',' && !inQuote) return [line.slice(0, i), line.slice(i + 1)]
  }
  return [line, '']
}

function parseAttrs(head: string): Record<string, string> {
  const attrs: Record<string, string> = {}
  const re = /([A-Za-z0-9_-]+)="([^"]*)"/g
  let m: RegExpExecArray | null
  while ((m = re.exec(head)) !== null) attrs[m[1].toLowerCase()] = m[2]
  return attrs
}

/**
 * `名字,地址` 从右往左找第一个后面紧跟协议头的逗号——
 * 频道名里可能带逗号，地址也可能是 rtp:// 这类。
 */
function splitNameURL(line: string): [string, string] {
  for (let i = line.length - 1; i >= 0; i--) {
    if (line[i] !== ',') continue
    if (looksLikeURL(line.slice(i + 1))) {
      return [line.slice(0, i).trim(), line.slice(i + 1).trim()]
    }
  }
  const parts = line.split(',')
  if (parts.length < 2) return ['', '']
  return [parts[0].trim(), parts.slice(1).join(',').trim()]
}

/**
 * 国内 TXT 源经常是 GBK：先按 UTF-8 严格解，失败再退 GBK。
 * 与 build.mjs 的 decodeBody 同一套顺序，否则同一份源在两边会解出不同的频道名。
 */
export function decodeSourceBytes(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf)
  if (bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) {
    return new TextDecoder('utf-8').decode(bytes.subarray(3))
  }
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    try {
      return new TextDecoder('gbk').decode(bytes)
    } catch {
      return new TextDecoder('utf-8').decode(bytes)
    }
  }
}

/** 判据照抄 build.mjs 的 detectFormat（后者又对齐 server/internal/parse 的 Detect） */
export function detectFormat(text: string): SourceFormat {
  const t = text.replace(/^\uFEFF/, '').trimStart()
  if (!t) return 'empty'
  if (t[0] === '{' || t[0] === '[') return 'json'
  if (t[0] === '<') return 'html'

  const head = t.slice(0, 512)
  if (head.includes('#EXTM3U') || head.includes('#EXTINF')) return 'm3u'
  if (head.includes('#genre#')) return 'txt'
  for (const line of head.split(/\r?\n/)) {
    if (line.includes(',') && line.includes('://')) return 'txt'
  }
  return 'unknown'
}

function parseM3U(text: string): PreviewItem[] {
  const items: PreviewItem[] = []
  let cur: {
    name: string
    tvgId: string
    logo: string
    group: string
    url: string
  } | null = null

  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim()
    if (!line) continue
    if (line.startsWith('#EXTM3U')) continue

    if (line.startsWith('#EXTINF')) {
      const [head, name] = splitExtinf(line)
      const attrs = parseAttrs(head)
      cur = {
        name: name.trim(),
        tvgId: attrs['tvg-id'] || '',
        logo: attrs['tvg-logo'] || '',
        group: attrs['group-title'] || '',
        url: '',
      }
      continue
    }

    if (line.startsWith('#EXTGRP:')) {
      if (cur && !cur.group) cur.group = line.slice(8).trim()
      continue
    }

    // #EXTVLCOPT / #KODIPROP 之类与预览无关：入库时的 UA/Referer 由 CI 抽，
    // 预览只需要看名字、分组和地址对不对。
    if (line.startsWith('#')) continue

    if (cur && !cur.url) {
      cur.url = line
      if (cur.name) {
        items.push({
          name: cur.name,
          url: cur.url,
          group: cur.group,
          tvg_id: cur.tvgId,
          logo: cur.logo,
        })
      }
      cur = null
    }
  }

  return items
}

/** TXT：`分组,#genre#` 切组，`频道名,地址` 一条频道 */
function parseTXT(text: string): PreviewItem[] {
  const items: PreviewItem[] = []
  let group = '未分组'

  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue

    if (line.includes('#genre#')) {
      const g = line.split(',')[0].replace('#genre#', '').trim()
      if (g) group = g
      continue
    }

    const [name, url] = splitNameURL(line)
    if (!url || !looksLikeURL(url)) continue
    items.push({ name, url, group })
  }

  return items
}

export interface ParsedSource {
  format: SourceFormat
  items: PreviewItem[]
}

/**
 * 频道名归一化：只抹掉空白与常见分隔符。
 *
 * ★ 必须与 `edge/scripts/build.mjs` 的 `normalizeName`、Android
 *   `M3uClient.normalizeName` 完全一致——三处算出的键不一致，就会出现
 *   「同一个台在管理台和电视上各算一条」或者「改名/屏蔽对不上」。
 */
export function normalizeChannelName(name: string): string {
  return name.toLowerCase().replace(/[\s\-_.·|/\\]+/g, '')
}

/**
 * 频道稳定键（= 库里的 `stable_key`）。
 *
 * 与 `build.mjs` 的 `dedupeKey`、`M3uClient` 构造频道时的 key 同一条规则：
 * 有 tvg-id 用 `id:`，否则用 `name:` + 归一化名。
 */
export function channelKey(item: { tvg_id?: string; name: string }): string {
  const id = (item.tvg_id || '').trim()
  return id ? `id:${id}` : `name:${normalizeChannelName(item.name)}`
}

/**
 * 解析一份源文本。json / html / empty / unknown 一律返回空列表——
 * 这不是错误，是「你填的可能是面板首页而不是订阅地址」，UI 会据此给提示。
 */
export function parseSourceText(text: string): ParsedSource {
  const format = detectFormat(text)
  if (format === 'm3u') return { format, items: parseM3U(text) }
  if (format === 'txt') return { format, items: parseTXT(text) }
  return { format, items: [] }
}
