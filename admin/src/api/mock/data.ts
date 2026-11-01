import type {
  Channel,
  ChannelStream,
  Device,
  EpgChannel,
  EpgSource,
  Source,
  SystemConfig,
} from '../types'

/** 生成一个内联 SVG 台标，避免 mock 预览依赖外网图片 */
export function logoSvg(label: string, hue: number): string {
  const text = label.slice(0, 4)
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96">` +
    `<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">` +
    `<stop offset="0" stop-color="hsl(${hue} 62% 44%)"/>` +
    `<stop offset="1" stop-color="hsl(${(hue + 42) % 360} 68% 26%)"/>` +
    `</linearGradient></defs>` +
    `<rect width="96" height="96" rx="18" fill="url(#g)"/>` +
    `<text x="48" y="50" font-family="system-ui,-apple-system,sans-serif" font-size="24" font-weight="700" ` +
    `fill="#ffffff" text-anchor="middle" dominant-baseline="middle">${text}</text></svg>`
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
}

function hueOf(seed: string): number {
  let h = 0
  for (let i = 0; i < seed.length; i++) h = (h * 31 + seed.charCodeAt(i)) % 360
  return h
}

const now = Date.now()
const minutesAgo = (m: number): string => new Date(now - m * 60_000).toISOString()
const hoursAgo = (h: number): string => new Date(now - h * 3_600_000).toISOString()
const daysAgo = (d: number): string => new Date(now - d * 86_400_000).toISOString()

export const seedSources: Source[] = [
  {
    id: 1,
    name: '央视频道 4K 源',
    type: 'm3u',
    url: 'https://example.com/iptv/cctv.m3u8',
    enabled: true,
    status: 'ok',
    last_fetch_at: minutesAgo(12),
    error: null,
    channel_count: 22,
  },
  {
    id: 2,
    name: '卫视频道合集',
    type: 'txt',
    url: 'https://example.com/iptv/weishi.txt',
    enabled: true,
    status: 'ok',
    last_fetch_at: hoursAgo(3),
    error: null,
    channel_count: 12,
  },
  {
    id: 3,
    name: 'TVBox 直播配置',
    type: 'tvbox',
    url: 'https://example.com/tvbox/live.json',
    enabled: true,
    status: 'error',
    last_fetch_at: daysAgo(2),
    error: '拉取失败：HTTP 403 Forbidden，源站可能换了地址',
    channel_count: 0,
  },
  {
    id: 4,
    name: '港澳台 JSON 源',
    type: 'json',
    url: 'https://example.com/iptv/hk.json',
    enabled: false,
    status: 'never',
    last_fetch_at: null,
    error: null,
    channel_count: 0,
  },
]

const channelSeeds: Array<[string, string, string]> = [
  ['CCTV-1 综合', '央视频道', 'cctv1'],
  ['CCTV-2 财经', '央视频道', 'cctv2'],
  ['CCTV-3 综艺', '央视频道', 'cctv3'],
  ['CCTV-4 中文国际', '央视频道', 'cctv4'],
  ['CCTV-5 体育', '央视频道', 'cctv5'],
  ['CCTV-5+ 体育赛事', '央视频道', 'cctv5plus'],
  ['CCTV-6 电影', '央视频道', 'cctv6'],
  ['CCTV-7 国防军事', '央视频道', 'cctv7'],
  ['CCTV-8 电视剧', '央视频道', 'cctv8'],
  ['CCTV-9 纪录', '央视频道', 'cctv9'],
  ['CCTV-10 科教', '央视频道', 'cctv10'],
  ['CCTV-11 戏曲', '央视频道', 'cctv11'],
  ['CCTV-12 社会与法', '央视频道', 'cctv12'],
  ['CCTV-13 新闻', '央视频道', 'cctv13'],
  ['CCTV-14 少儿', '央视频道', 'cctv14'],
  ['CCTV-15 音乐', '央视频道', 'cctv15'],
  ['CCTV-16 奥林匹克', '央视频道', 'cctv16'],
  ['CCTV-17 农业农村', '央视频道', 'cctv17'],
  ['CGTN', '央视频道', 'cgtn'],
  ['CCTV-4K 超高清', '央视频道', 'cctv4k'],
  ['湖南卫视', '卫视频道', 'hunan'],
  ['浙江卫视', '卫视频道', 'zhejiang'],
  ['江苏卫视', '卫视频道', 'jiangsu'],
  ['东方卫视', '卫视频道', 'dongfang'],
  ['北京卫视', '卫视频道', 'beijing'],
  ['广东卫视', '卫视频道', 'guangdong'],
  ['深圳卫视', '卫视频道', 'shenzhen'],
  ['安徽卫视', '卫视频道', 'anhui'],
  ['山东卫视', '卫视频道', 'shandong'],
  ['湖北卫视', '卫视频道', 'hubei'],
  ['四川卫视', '卫视频道', 'sichuan'],
  ['河南卫视', '卫视频道', 'henan'],
  ['北京新闻', '地方频道', 'bjnews'],
  ['上海新闻综合', '地方频道', 'shnews'],
  ['广州综合', '地方频道', 'gztv'],
  ['杭州西湖明珠', '地方频道', 'hzxhmz'],
  ['成都新闻综合', '地方频道', 'cdnews'],
  ['翡翠台', '港澳台', 'tvbjade'],
  ['明珠台', '港澳台', 'tvbpearl'],
  ['凤凰卫视中文台', '港澳台', 'phoenixcn'],
  ['凤凰资讯台', '港澳台', 'phoenixinfo'],
  ['TVBS 新闻', '港澳台', 'tvbsn'],
  ['东森新闻', '港澳台', 'ettoday'],
  ['五星体育', '体育赛事', 'wuxing'],
  ['广东体育', '体育赛事', 'gdsports'],
  ['咪咕体育', '体育赛事', 'migu'],
  ['CCTV-高尔夫网球', '体育赛事', 'golf'],
  ['电影频道 CHC', '影视剧集', 'chc'],
  ['淘剧场', '影视剧集', 'taojuchang'],
  ['动漫秀场', '影视剧集', 'anime'],
  ['纪实人文', '影视剧集', 'jishi'],
]

export const seedChannels: Channel[] = channelSeeds.map(([name, group, tvg], i) => ({
  id: i + 1,
  number: i + 1,
  name,
  group,
  logo: logoSvg(name.replace(/^(CCTV-|CGTN)/, 'CCTV'), hueOf(name)),
  tvg_id: tvg,
  epg_id: i % 7 === 3 ? null : `${tvg}.epg`,
  enabled: i % 13 !== 5,
  stream_count: (i % 3) + 1,
}))

export const seedStreams: Record<number, ChannelStream[]> = {}
seedChannels.forEach((c) => {
  const n = c.stream_count ?? 1
  const list: ChannelStream[] = []
  for (let k = 0; k < n; k++) {
    list.push({
      id: c.id * 10 + k,
      url: `http://live.example.com/${c.tvg_id}/line${k + 1}.m3u8`,
      source_id: k === 0 ? 1 : 2,
      source_name: k === 0 ? '央视频道 4K 源' : '卫视频道合集',
      quality: k === 0 ? '1080P · 4.2Mbps' : '720P · 2.1Mbps',
      enabled: true,
      latency_ms: 60 + ((c.id * 17 + k * 23) % 220),
    })
  }
  seedStreams[c.id] = list
})

export const seedEpgSources: EpgSource[] = [
  {
    id: 1,
    name: 'EPG 中文总表（xmltv）',
    url: 'https://example.com/epg/cn.xml.gz',
    enabled: true,
    last_refresh_at: minutesAgo(48),
    channel_count: 168,
    program_count: 12043,
    error: null,
  },
  {
    id: 2,
    name: '港澳台 EPG',
    url: 'https://example.com/epg/hk.xml',
    enabled: true,
    last_refresh_at: daysAgo(3),
    channel_count: 42,
    program_count: 2108,
    error: '上次刷新超时，已用旧数据顶着',
  },
]

export const seedEpgChannels: EpgChannel[] = [
  { id: 'cctv1.epg', name: 'CCTV-1 综合 HD', source: 'EPG 中文总表（xmltv）', program_count: 148 },
  { id: 'cctv2.epg', name: 'CCTV-2 财经 HD', source: 'EPG 中文总表（xmltv）', program_count: 132 },
  { id: 'cctv3.epg', name: 'CCTV-3 综艺 HD', source: 'EPG 中文总表（xmltv）', program_count: 96 },
  { id: 'cctv4.epg', name: 'CCTV-4 中文国际', source: 'EPG 中文总表（xmltv）', program_count: 110 },
  { id: 'cctv5.epg', name: 'CCTV-5 体育 HD', source: 'EPG 中文总表（xmltv）', program_count: 88 },
  { id: 'cctv5plus.epg', name: 'CCTV-5+ 体育赛事', source: 'EPG 中文总表（xmltv）', program_count: 74 },
  { id: 'cctv6.epg', name: 'CCTV-6 电影 HD', source: 'EPG 中文总表（xmltv）', program_count: 62 },
  { id: 'cctv8.epg', name: 'CCTV-8 电视剧 HD', source: 'EPG 中文总表（xmltv）', program_count: 78 },
  { id: 'cctv13.epg', name: 'CCTV-13 新闻 HD', source: 'EPG 中文总表（xmltv）', program_count: 152 },
  { id: 'cctv14.epg', name: 'CCTV-14 少儿 HD', source: 'EPG 中文总表（xmltv）', program_count: 120 },
  { id: 'hunan.epg', name: '湖南卫视 HD', source: 'EPG 中文总表（xmltv）', program_count: 84 },
  { id: 'zhejiang.epg', name: '浙江卫视 HD', source: 'EPG 中文总表（xmltv）', program_count: 80 },
  { id: 'jiangsu.epg', name: '江苏卫视 HD', source: 'EPG 中文总表（xmltv）', program_count: 76 },
  { id: 'dongfang.epg', name: '东方卫视 HD', source: 'EPG 中文总表（xmltv）', program_count: 92 },
  { id: 'beijing.epg', name: '北京卫视 HD', source: 'EPG 中文总表（xmltv）', program_count: 70 },
  { id: 'guangdong.epg', name: '广东卫视 HD', source: 'EPG 中文总表（xmltv）', program_count: 66 },
  { id: 'shenzhen.epg', name: '深圳卫视 HD', source: 'EPG 中文总表（xmltv）', program_count: 64 },
  { id: 'anhui.epg', name: '安徽卫视', source: 'EPG 中文总表（xmltv）', program_count: 60 },
  { id: 'shandong.epg', name: '山东卫视', source: 'EPG 中文总表（xmltv）', program_count: 58 },
  { id: 'hubei.epg', name: '湖北卫视', source: 'EPG 中文总表（xmltv）', program_count: 56 },
  { id: 'sichuan.epg', name: '四川卫视', source: 'EPG 中文总表（xmltv）', program_count: 54 },
  { id: 'henan.epg', name: '河南卫视', source: 'EPG 中文总表（xmltv）', program_count: 52 },
  { id: 'tvbjade.epg', name: '翡翠台 (TVB)', source: '港澳台 EPG', program_count: 48 },
  { id: 'tvbpearl.epg', name: '明珠台 (TVB Pearl)', source: '港澳台 EPG', program_count: 44 },
  { id: 'phoenixcn.epg', name: '凤凰卫视中文台', source: '港澳台 EPG', program_count: 40 },
  { id: 'phoenixinfo.epg', name: '凤凰资讯台', source: '港澳台 EPG', program_count: 42 },
  { id: 'tvbsn.epg', name: 'TVBS 新闻台', source: '港澳台 EPG', program_count: 38 },
  { id: 'ettoday.epg', name: '东森新闻 CH51', source: '港澳台 EPG', program_count: 36 },
  { id: 'golf.epg', name: 'CCTV 高尔夫网球', source: 'EPG 中文总表（xmltv）', program_count: 30 },
  { id: 'chc.epg', name: 'CHC 家庭影院', source: 'EPG 中文总表（xmltv）', program_count: 34 },
]

export const seedDevices: Device[] = [
  {
    id: 101,
    name: '客厅电视',
    platform: 'Android TV',
    model: '小米电视 4A 55"',
    online: true,
    last_heartbeat_at: minutesAgo(1),
    status: 'bound',
    ip: '192.168.1.21',
    app_version: '1.2.0',
    config: { decode: 'hard', buffer_ms: 1200, aspect: 'auto', autoplay: true },
  },
  {
    id: 102,
    name: '卧室盒子',
    platform: 'Android',
    model: '当贝 H3',
    online: false,
    last_heartbeat_at: hoursAgo(9),
    status: 'bound',
    ip: '192.168.1.34',
    app_version: '1.2.0',
    config: { decode: 'auto', buffer_ms: 2000, aspect: '16:9', autoplay: false },
  },
  {
    id: 103,
    name: '书房 iPad',
    platform: 'iOS',
    model: 'iPad Air 5',
    online: true,
    last_heartbeat_at: minutesAgo(3),
    status: 'bound',
    ip: '192.168.1.57',
    app_version: '1.1.4',
    config: null,
  },
  {
    id: 104,
    name: '爸妈家机顶盒',
    platform: 'Android',
    model: 'N1 盒子',
    online: false,
    last_heartbeat_at: daysAgo(6),
    status: 'bound',
    ip: '10.0.0.8',
    app_version: '1.0.9',
    config: { decode: 'soft', buffer_ms: 3000, aspect: 'fill', autoplay: true },
  },
  {
    id: 105,
    name: '新设备（等待批准）',
    platform: 'Android TV',
    model: 'Sony X90K',
    online: true,
    last_heartbeat_at: minutesAgo(0),
    status: 'pending',
    ip: '192.168.1.88',
    app_version: '1.2.0',
    config: null,
  },
]

export const seedConfig: SystemConfig = {
  port: 9527,
  max_concurrent: 6,
  transcode_enabled: false,
  log_level: 'info',
  epg_refresh_hour: 4,
  allow_lan: true,
}

const logTemplates = [
  'source: 开始拉取 #1 央视频道 4K 源 (m3u)',
  'source: #1 解析完成，共 22 个频道，去重后新增 3 条',
  'epg: 刷新 EPG 中文总表，写入 12043 条节目单',
  'stream: 频道 CCTV-1 综合 上游连接建立，缓冲 3 个 GOP',
  'stream: 频道 CCTV-5 体育 客户端断开，剩余 1 路',
  'probe: 频道 翡翠台 探测失败，超时 8000ms，已降权',
  'device: 设备 客厅电视 (192.168.1.21) 心跳正常',
  'device: 新设备请求绑定，等待管理台批准',
  'transcode: 转码任务已关闭（配置 transcode_enabled=false）',
  'admin: 用户 admin 通过网关访问 /app/litv/',
  'http: GET /api/v1/channels 200 12ms',
  'warn: 频道 东森新闻 的源地址返回 404，已自动切换到备用线路',
]

export function seedLogs(count = 220): string[] {
  const out: string[] = []
  for (let i = count - 1; i >= 0; i--) {
    const t = new Date(now - i * 41_000)
    const line = logTemplates[(i * 7 + 3) % logTemplates.length]
    const pad = (n: number) => String(n).padStart(2, '0')
    out.push(
      `${t.getFullYear()}-${pad(t.getMonth() + 1)}-${pad(t.getDate())} ${pad(t.getHours())}:${pad(
        t.getMinutes(),
      )}:${pad(t.getSeconds())} ${i % 11 === 0 ? 'WARN' : 'INFO'} ${line}`,
    )
  }
  return out
}

export interface MockDb {
  sources: Source[]
  channels: Channel[]
  streams: Record<number, ChannelStream[]>
  epgSources: EpgSource[]
  epgChannels: EpgChannel[]
  devices: Device[]
  config: SystemConfig
  logs: string[]
  playsToday: number
  nextId: number
}

export function createDb(): MockDb {
  return {
    sources: JSON.parse(JSON.stringify(seedSources)) as Source[],
    channels: JSON.parse(JSON.stringify(seedChannels)) as Channel[],
    streams: JSON.parse(JSON.stringify(seedStreams)) as Record<number, ChannelStream[]>,
    epgSources: JSON.parse(JSON.stringify(seedEpgSources)) as EpgSource[],
    epgChannels: JSON.parse(JSON.stringify(seedEpgChannels)) as EpgChannel[],
    devices: JSON.parse(JSON.stringify(seedDevices)) as Device[],
    config: { ...seedConfig },
    logs: seedLogs(),
    playsToday: 137,
    nextId: 5000,
  }
}

/** 预览用的假数据（含几条脏数据，方便演示"调试脏数据"这个场景） */
export function previewItems() {
  const items = seedChannels.slice(0, 50).map((c) => ({
    name: c.name,
    url: `http://live.example.com/${c.tvg_id}/index.m3u8`,
    group: c.group,
    tvg_id: c.tvg_id,
    logo: c.logo,
  }))
  items.push({ name: '未命名频道', url: '', group: '', tvg_id: '', logo: '' })
  items.push({
    name: '测试线路 #EXTINF 缺失',
    url: 'rtmp://example.com/live/test',
    group: '未分组',
    tvg_id: '',
    logo: '',
  })
  return items
}

export function epgProgramsFor(channelId: number, day = 0) {
  const ch = seedChannels.find((c) => c.id === channelId)
  const base = new Date(now + day * 86_400_000)
  base.setHours(0, 0, 0, 0)
  const titles = [
    ['朝闻天下', '新闻联播', '焦点访谈', '电视剧：山海情 第 12 集', '晚间新闻', '纪录片：航拍中国'],
    ['第一时间', '整点新闻', '体育世界', '电影：流浪地球', '深度观察', '午夜剧场'],
    ['生活提示', '午间新闻', '健康之路', '综艺：开讲啦', '法治在线', '午夜剧场'],
  ]
  const pool = titles[channelId % titles.length]
  const out: Array<{ start: string; end: string; title: string; desc: string }> = []
  let hour = 6
  for (let i = 0; i < 8; i++) {
    const start = new Date(base.getTime() + hour * 3_600_000)
    const end = new Date(start.getTime() + 2 * 3_600_000)
    out.push({
      start: start.toISOString(),
      end: end.toISOString(),
      title: pool[i % pool.length],
      desc: `${ch?.name ?? '频道'} ${pool[i % pool.length]}（示例节目单）`,
    })
    hour += 2
  }
  return out
}
