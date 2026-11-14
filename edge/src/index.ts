// 边缘版「最小 litvd」+ 管理台后端。
//
// 目标：让现有的 Android TV / 手机 / iOS 客户端在**零改动**的前提下，
// 连上 Cloudflare 也能看电视。靠的是两个客户端本来就有的能力：
//
//   1. /api/v1/tv/play/{id} 返回 mode="direct" + 上游绝对地址 + headers 时，
//      客户端自己直连源站（见 android Api.absolute / PlayInfo.playHeaders）
//   2. 绑定页本来就是「服务器地址 + 6 位数字绑定码」两个输入框
//
// 所以这里只实现协议，不碰任何媒体字节。没有中转、没有 ffmpeg、没有磁盘。
//
// 免费版约束（决定了代码长什么样）：
//   - 每次请求 10ms CPU → 只做点查询和拼 JSON，绝不解析大文件
//   - 100k 请求/天 → 静态资源不计数，接口够用
//   - 50 subrequest/请求 → 每个接口最多 3 次 D1 查询

import {
  BOOTSTRAP_SQL,
  CHANNELS_VERSION_SQL,
  DEFAULT_DEVICE_CONFIG,
  EPG_NOW_LOOKBACK_SEC,
  NOT_BLOCKED_SQL,
} from "./bootstrap.sql.js";
// 带 .ts 后缀是有意的：scripts/workertest.mjs 会用 Node 直接 import 这个文件
// （Node 的 ESM 要求显式扩展名），打包侧 esbuild 也认。
import { checkFetchTarget, fetchGuarded } from "./net.ts";

interface Env {
  ASSETS: Fetcher;
  DB: D1Database;
  BIND_CODE: string;
  // 管理台口令。配了它以后 BIND_CODE 只能用来绑定设备，不能再登录管理台。
  // 不配时退回 BIND_CODE（兼容老部署，但那是弱口令，见 adminPasscode）。
  ADMIN_PASSWORD?: string;
  SERVER_NAME?: string;
  // 节目单的日边界用哪个时区算，默认东八区
  TZ_NAME?: string;
  // 可选：管理台入口路径（如 litv-9f3a）。空 = 根路径就是入口。
  ADMIN_PATH?: string;
  // 可选：配了才能在管理台里一键触发重建。没配就只靠定时任务。
  GITHUB_DISPATCH_TOKEN?: string;
  // 「立即重建」要 dispatch 的目标。正常由 CI 用 --var 注入真实仓库，
  // wrangler.toml 的 [vars] 里有兜底值；类型上仍标可选——
  // 手工部署漏配时要能给出人话提示，而不是崩在一个 undefined 上。
  GITHUB_REPO?: string;
  GITHUB_WORKFLOW?: string;
  GITHUB_REF?: string;
}

// 设备默认配置（decode / buffer_ms / aspect / autoplay / engine）现在写在
// src/bootstrap.sql.js 里，跟响应体一起由 SQLite 生成。改的时候只改那一处。

const ADMIN_SESSION_SEC = 7 * 24 * 3600;
const RATE_WINDOW_SEC = 600;
const RATE_MAX_FAILS = 10;
// playlist 是客户端启动时自动拉的，正常用户可能同时开几台设备。
// 给它更宽的次数上限，同时用**更短的窗口**（1 分钟）：
// 旧客户端拿着已作废的 BIND_CODE 高频重试时，只会让同一出口 IP 的
// playlist 短暂 404，而不是像绑定码那样一锁 10 分钟。
const PLAYLIST_MAX_FAILS = 20;
const PLAYLIST_WINDOW_SEC = 60;

// ---------------------------------------------------------------- 基础设施

function json(body: unknown, status = 200): Response {
  // 注意：Android 的健康检查是靠字符串搜 `"code":0`，所以不能有空格。
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

function ok(data: unknown): Response {
  return json({ code: 0, msg: "ok", data });
}

// 错误码对齐真服务端（server/internal/api）：
//   1001 参数错 / 2001 未认证 / 2002 无权限 / 2003 设备未绑定
//   2004 不存在 / 2005 限速 / 2006 边缘模式不支持
//
// 2001 与 1001 绝不能混用：管理台的 `isUnauthorized` 把 2001 判成「会话失效」
// （admin/src/api/errors.ts），拿它表达「地址格式不对」会把用户整页踢到
// 「请重新登录」上，而他填错的是一个源地址。
//
// status 默认 200：客户端普遍只看信封里的 code。但**未认证**要额外给 401，
// 那是对齐服务端的，也让管理台的原生 HTTP 分支能兜住。
function fail(code: number, msg: string, status = 200): Response {
  return json({ code, msg, data: null }, status);
}

/**
 * 管理台未登录 / 会话过期。
 *
 * 必须是 **2001 + 401**，不能是 2003：
 *   - 2003 是「设备未绑定」的语义，客户端靠这个词跳回绑定页；
 *   - 管理台只把 2001（或 HTTP 401）当成「需要重新登录」
 *     （admin/src/api/errors.ts 的 isUnauthorized），拿到别的码只会弹一条
 *     红条然后停在死页面上——7 天会话到期后就没有登录入口了。
 * 真服务端同样是 401 + 2001（server/internal/api/handlers_auth.go）。
 */
function failAdminUnauthorized(): Response {
  return fail(2001, "管理台未登录或会话已过期", 401);
}

/**
 * 频道列表版本号由 bootstrap.sql.js 里的 CHANNELS_VERSION_SQL 读（kv 计数器）。
 *
 * 为什么是计数器而不是像服务端那样现算指纹：指纹要扫全表，而心跳 30 秒一次，
 * D1 免费额度的读取量扛不住（服务端跑在 NAS 本地 SQLite 上，没有这个问题）。
 *
 * 为什么风险可控：**推进动作只在两个地方**——重建（meta.sql）与管理台的
 * 写请求（路由层统一处理，不逐个 handler 加）。漏不了，而且每小时重建
 * 必然 +1，最坏情况只是「改完最多一小时客户端才刷新」。
 */
async function bumpChannelsVersion(env: Env): Promise<void> {
  await env.DB.prepare(
    `INSERT INTO kv (k, v) VALUES ('channels_version', '1')
     ON CONFLICT(k) DO UPDATE SET v = CAST(CAST(v AS INTEGER) + 1 AS TEXT)`,
  ).run();
}

async function sha256hex(s: string): Promise<string> {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

// 定长比较，避免按字节提前返回泄露口令前缀
function safeEqual(a: string, b: string): boolean {
  const ab = new TextEncoder().encode(a);
  const bb = new TextEncoder().encode(b);
  if (ab.length !== bb.length) return false;
  let diff = 0;
  for (let i = 0; i < ab.length; i++) diff |= ab[i] ^ bb[i];
  return diff === 0;
}

function randomToken(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function nowSec(): number {
  return Math.floor(Date.now() / 1000);
}

function bearer(req: Request): string {
  const m = /^Bearer\s+(.+)$/i.exec(req.headers.get("authorization") || "");
  return m ? m[1].trim() : "";
}

/**
 * 失败计数与限速。
 *
 * 旧写法是「先 SELECT 判断是否超限 → 校验 → 再 UPDATE +1」，两条语句之间
 * 有窗口：并发请求会一起读到 fails=9、一起放行，限速形同虚设。
 *
 * 现在合并成一条 UPSERT，并且**先占名额再校验口令**：
 * 计数是原子的，并发下最多只有 RATE_MAX_FAILS 次校验会真正执行。
 * 成功时 clearFailures 把计数清零，所以合法用户不会累积。
 */
async function bumpAttempt(env: Env, key: string, windowSec = RATE_WINDOW_SEC): Promise<number> {
  const now = nowSec();
  const row = await env.DB.prepare(
    `INSERT INTO bind_attempts (ip, fails, window_start) VALUES (?, 1, ?)
     ON CONFLICT(ip) DO UPDATE SET
       fails = CASE WHEN ? - bind_attempts.window_start >= ?
                    THEN 1 ELSE bind_attempts.fails + 1 END,
       window_start = CASE WHEN ? - bind_attempts.window_start >= ?
                           THEN ? ELSE bind_attempts.window_start END
     RETURNING fails`,
  )
    .bind(key, now, now, windowSec, now, windowSec, now)
    .first<{ fails: number }>();
  if (!row) {
    // 解释器不支持 RETURNING 时的兜底：读一次当前值（限速会略宽，但不会失效）
    const cur = await env.DB.prepare("SELECT fails FROM bind_attempts WHERE ip = ?")
      .bind(key)
      .first<{ fails: number }>();
    return cur ? Number(cur.fails) : 0;
  }
  return Number(row.fails);
}

async function clearFailures(env: Env, key: string): Promise<void> {
  await env.DB.prepare("DELETE FROM bind_attempts WHERE ip = ?").bind(key).run();
}

/**
 * 只读地看一眼当前窗口内的失败次数（不写库）。
 *
 * 给 playlist 用：播放列表是客户端自动轮询的，用 `bumpAttempt` 的话
 * **每一次正常请求**都要写一行再删一行（2 行写入）。免费版 D1 的写入额度
 * 是按行算的，第三方播放器每几分钟拉一次就能把额度吃掉。
 *
 * 换成「先读、错了才写」：正常请求 0 行写入，猜错才计数。
 * 限速的保护能力不变——猜错的那条路径仍然是原子的 UPSERT。
 */
async function currentFailures(env: Env, key: string, windowSec: number): Promise<number> {
  const row = await env.DB.prepare(
    "SELECT fails, window_start FROM bind_attempts WHERE ip = ?",
  )
    .bind(key)
    .first<{ fails: number; window_start: number }>();
  if (!row) return 0;
  // 窗口已经过了 → 计数作废（下次猜错会从 1 重新开始）
  if (nowSec() - Number(row.window_start) >= windowSec) return 0;
  return Number(row.fails);
}

function clientIp(req: Request): string {
  return req.headers.get("cf-connecting-ip") || "unknown";
}

// ---------------------------------------------------------------- 认证

interface DeviceRow {
  id: number;
  name: string;
  platform: string;
}

async function authDevice(req: Request, env: Env): Promise<DeviceRow | null> {
  const token = bearer(req);
  if (!token) return null;
  const hash = await sha256hex(token);
  const row = await env.DB.prepare(
    "SELECT id, name, platform FROM devices WHERE token_hash = ?",
  )
    .bind(hash)
    .first<DeviceRow>();
  return row ?? null;
}

async function authAdmin(req: Request, env: Env): Promise<boolean> {
  const token = bearer(req);
  if (!token) return false;
  const hash = await sha256hex(token);
  const row = await env.DB.prepare(
    "SELECT expires_at FROM admin_sessions WHERE token_hash = ?",
  )
    .bind(hash)
    .first<{ expires_at: number }>();
  return row !== null && row.expires_at > nowSec();
}

// ---------------------------------------------------------------- 公共接口

async function health(env: Env): Promise<Response> {
  return ok({
    name: env.SERVER_NAME || "LITV Edge",
    version: "edge",
    mode: "direct",
    time: nowSec(),
  });
}

/**
 * 客户端自报的 platform 只用于展示与冲突检测，绝不能当权限用。
 *
 * 口径要与 Go 服务端保持一致：trim + 小写 + 截断，只挡保留值。
 * 这里以前还加了字符集白名单，结果 "Android TV" 这类值在 NAS 上能注册、
 * 在边缘版却被拒——平台名本来就不参与鉴权，没必要多一套规则。
 * 控制字符会污染日志与管理台展示，直接剔掉。
 */
function normalizePlatform(raw: unknown): string {
  return String(raw ?? "")
    .trim()
    .toLowerCase()
    .replace(/[\u0000-\u001f\u007f]/g, "")
    .slice(0, 32);
}

async function deviceRegister(req: Request, env: Env): Promise<Response> {
  if (!env.BIND_CODE) return fail(2001, "服务端未配置绑定码");

  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(1001, "请求体不是合法 JSON");
  }

  // 绑定码只有 6 位数字（客户端输入框限死了），空间只有 100 万，
  // 不挡一下等于把设备注册开放给暴力枚举。
  const ip = clientIp(req);
  // 桶名带前缀：绑定码与管理台口令是两个用途，共用一个桶时
  // 家里电视输错 10 次绑定码，会连带把管理台登录一起锁 10 分钟。
  const attempt = await bumpAttempt(env, `bind:${ip}`);
  if (attempt > RATE_MAX_FAILS) return fail(2005, "尝试次数过多，请 10 分钟后再试");

  const code = String(body.code ?? "");
  if (!safeEqual(code, env.BIND_CODE)) {
    return fail(2001, "绑定码不正确");
  }
  await clearFailures(env, `bind:${ip}`);

  const deviceKey = String(body.device_key ?? "").slice(0, 128);
  if (!deviceKey) return fail(1001, "缺少 device_key");

  // 保留值必须挡住：服务端曾经有过 `platform == "admin"` 就提权的实现，
  // 留着这个口子等于把提权路径留在数据里。
  let platform = normalizePlatform(body.platform);
  if (!platform) platform = "android";
  if (platform === "admin") return fail(2002, "platform 为保留值");
  const name = String(body.name ?? "").slice(0, 64);
  const ts = nowSec();

  // 对齐服务端语义：同一个 device_key 换平台必须拒绝，
  // 否则知道 key 的人就能顶掉这台设备。
  //
  // 历史数据里 platform 可能是空串（早期客户端没上报），那种行按
  // 「平台未知」处理、允许补写；只要已有值就必须完全一致。
  // 早期写法没转小写，旧行里可能是 "iOS" 这种混合大小写，这里按小写比较，
  // 命中后顺手把新值写回去，等于就地迁移。
  const existing = await env.DB.prepare(
    "SELECT id, platform FROM devices WHERE device_key = ?",
  )
    .bind(deviceKey)
    .first<{ id: number; platform: string }>();

  if (existing && existing.platform && existing.platform.trim().toLowerCase() !== platform) {
    return fail(2002, "该设备已用其他平台注册");
  }

  const token = randomToken();
  const hash = await sha256hex(token);

  let deviceId: number;
  if (existing) {
    await env.DB.prepare(
      "UPDATE devices SET token_hash = ?, name = ?, platform = ?, last_seen = ? WHERE id = ?",
    )
      .bind(hash, name, platform, ts, existing.id)
      .run();
    deviceId = existing.id;
  } else {
    const res = await env.DB.prepare(
      "INSERT INTO devices (device_key, token_hash, platform, name, created_at, last_seen) VALUES (?,?,?,?,?,?)",
    )
      .bind(deviceKey, hash, platform, name, ts, ts)
      .run();
    deviceId = Number(res.meta.last_row_id);
  }

  return ok({ device_id: deviceId, token, status: "bound", hint: "" });
}

async function tvBootstrap(env: Env, device: DeviceRow): Promise<Response> {
  const now = nowSec();
  // **这里不写 last_seen**。
  //
  // 以前每次 bootstrap 都更新一次（带 2 分钟节流），但设备在线状态本来
  // 就由 30 秒一次的心跳维护（`tvHeartbeat`），bootstrap 再写一次等于
  // 每次启动多一次 D1 往返 + 最多一行写入，纯浪费。
  // 客户端启动必 bootstrap、也必发心跳，去掉它不影响「在线」判定的准确性。
  //
  // 整个响应体在 SQLite 里拼成 JSON 字符串，Worker 只负责吐字节。
  // 不在 JS 里 map + JSON.stringify，所以 CPU 开销与频道数无关——
  // 这是这个接口能撑住几千个频道的原因。D1 的查询耗时算 wall time，
  // 不占 Worker 那 10ms 的 CPU 预算。
  //
  // 参数顺序必须与 bootstrap.sql.js 里的 `?` 出现次序一致（那里有说明）。
  // 错位**不会报错**，只会静默查不到数据（比如收藏永远为空），所以改任一边都要同步。
  const row = await env.DB.prepare(BOOTSTRAP_SQL)
    .bind(
      env.SERVER_NAME || "LITV", // 1. server.name
      device.id, // 2. device.id
      device.name || "TV", // 3. device.name
      device.id, // 4. 配置
      device.id, // 5. 配置版本
      device.id, // 6. 收藏（按设备）
      now - EPG_NOW_LOOKBACK_SEC, // 7. 「正在播」窗口起点
      now, // 8. 「正在播」窗口终点
      now, // 9. end_at > now
    )
    .first<{ body: string }>();

  if (!row || !row.body) return fail(2004, "频道表为空，先跑一次部署");

  return new Response(row.body, {
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

interface StreamRow {
  id: number;
  channel_id: number;
  label: string;
  quality: string;
  priority: number;
  health: number;
  latency_ms: number;
  url: string;
  ua: string;
  headers: string;
  origin: string;
  name?: string;
  logo?: string;
}

function parseHeaders(raw: string): Record<string, string> {
  try {
    const obj = JSON.parse(raw || "{}");
    if (obj && typeof obj === "object" && !Array.isArray(obj)) {
      const out: Record<string, string> = {};
      for (const [k, v] of Object.entries(obj)) {
        if (typeof v === "string" && v) out[k] = v;
      }
      return out;
    }
  } catch {
    // 存坏了就当没有，别让一条脏数据把播放彻底搞挂
  }
  return {};
}

async function tvPlay(env: Env, channelId: number, url: URL): Promise<Response> {
  const wantStream = Number(url.searchParams.get("stream_id") || "0");

  const sql = `
    SELECT s.id, s.channel_id, s.label, s.quality, s.priority, s.health,
           s.latency_ms, s.url, s.ua, s.headers, s.origin,
           COALESCE(o.name, c.name) AS name,
           COALESCE(o.logo, c.logo) AS logo,
           -- 实时读源上的 UA。UA 是**播放时**的属性，不该逼用户为了改它重建
           -- 整份频道表（重建要跑 CI、要等一两分钟，还得处理缩水护栏）。
           -- 只在「恰好一个启用的源配了 UA」时采用：多源部署下每条线路可能来自
           -- 不同的源，那时用 build 时写进 streams 的快照才准确。
           (SELECT CASE WHEN COUNT(*) = 1 THEN MAX(ua) END
              FROM sources WHERE enabled = 1 AND ua <> '') AS live_ua
      FROM streams s
      JOIN channels c ON c.id = s.channel_id
      LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key
     WHERE s.channel_id = ?${wantStream > 0 ? " AND s.id = ?" : ""}
     ORDER BY (s.health = 1) DESC, s.priority DESC, s.id ASC
     LIMIT 1`;

  const stmt = env.DB.prepare(sql);
  type PlayRow = StreamRow & { live_ua: string | null };
  const row = wantStream > 0
    ? await stmt.bind(channelId, wantStream).first<PlayRow>()
    : await stmt.bind(channelId).first<PlayRow>();

  // 3001/3002 与服务端一致：客户端对这两个码有专门文案
  // （3001「频道不存在，可能已被删除」/ 3002「这个频道没有可用线路」）
  if (!row) return fail(3002, "该频道没有可用线路");

  // 单源部署：源上刚改的 UA 立刻生效，不用重建。
  // 多源部署：回落到 build 快照（那时每条线路的来源是确定的）。
  const effectiveUa = String(row.live_ua ?? "") || row.ua || "";

  const headers = parseHeaders(row.headers);
  if (effectiveUa && !Object.keys(headers).some((k) => k.toLowerCase() === "user-agent")) {
    headers["User-Agent"] = effectiveUa;
  }

  // 客户端拿到 http(s) 绝对地址会原样使用（Api.absolute）。
  // mode=direct 表示"你自己去连源站"，服务端不转发任何字节。
  return ok({
    channel: { id: row.channel_id, name: row.name ?? "", logo: row.logo ?? "" },
    kind: /\.m3u8(\?|$)/i.test(row.url) ? "hls" : "ts",
    url: row.url,
    expires: nowSec() + 3600,
    stream_id: row.id,
    ua: effectiveUa,
    mode: "direct",
    headers,
  });
}

async function tvStreams(env: Env, channelId: number): Promise<Response> {
  const { results } = await env.DB.prepare(
    `SELECT id, label, quality, priority, health, latency_ms
       FROM streams WHERE channel_id = ?
      ORDER BY (health = 1) DESC, priority DESC, id ASC`,
  )
    .bind(channelId)
    .all();

  // 注意：不返回 url。终端只需要标签和健康度。
  return ok(results ?? []);
}

// 节目单的日边界必须按**当地日历日**算，不能按 UTC 零点截断——
// 东八区用户会看到节目单整体错位 8 小时。
function tzOffsetSec(tz: string, atSec: number): number {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: tz,
    hour12: false,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).formatToParts(new Date(atSec * 1000));
  const g = (t: string) => Number(parts.find((p) => p.type === t)?.value ?? 0);
  const asUTC = Date.UTC(
    g("year"),
    g("month") - 1,
    g("day"),
    g("hour") % 24,
    g("minute"),
    g("second"),
  );
  return Math.round(asUTC / 1000) - atSec;
}

function localDayRange(nowSec: number, tz: string, day: number): [number, number] {
  const off = tzOffsetSec(tz, nowSec);
  const localMidnight = Math.floor((nowSec + off) / 86400) * 86400 + day * 86400;
  const start = localMidnight - off;
  return [start, start + 86400];
}

interface EpgRow {
  id: number;
  channel_id: number;
  start_at: number;
  end_at: number;
  title: string;
  description: string;
  category: string;
}

async function tvEpg(env: Env, channelId: number, url: URL): Promise<Response> {
  const ch = await env.DB.prepare(
    `SELECT c.id,
            c.number,
            COALESCE(o.name, c.name) AS name,
            COALESCE(o.group_name, c."group") AS grp,
            COALESCE(o.logo, c.logo) AS logo,
            c.tvg_id,
            COALESCE(c.epg_id, '') AS epg_id,
            c.sort
       FROM channels c
       LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key
      WHERE c.id = ?`,
  )
    .bind(channelId)
    .first<{
      id: number;
      number: number;
      name: string;
      grp: string;
      logo: string;
      tvg_id: string;
      epg_id: string;
      sort: number;
    }>();

  // 3001 与服务端一致（客户端把它映射成「频道不存在，可能已被删除」）
  if (!ch) return fail(3001, "频道不存在");

  const day = Math.max(-7, Math.min(7, Number(url.searchParams.get("day") || "0") || 0));
  const now = nowSec();
  const tz = env.TZ_NAME || "Asia/Shanghai";
  const [start, end] = localDayRange(now, tz, day);

  // 日边界和时区一起下发，客户端不要自己按设备时区算
  const date = new Date((start + tzOffsetSec(tz, start)) * 1000).toISOString().slice(0, 10);

  if (!ch.epg_id) {
    return ok({ channel: ch, programs: [], note: "该频道未关联 EPG" });
  }

  const { results } = await env.DB.prepare(
    // 按 **xmltv_id** 查，不是 channel_id。
    //
    // 一个节目源会被多个频道共用：「央视」和「央视-MCP」里都有 CCTV1，
    // 是同一路信号的不同来源。而 build 写库时 channel_id 只能挂其中一个
    // （`if (!keyOf.has(xmltv)) keyOf.set(xmltv, key)`），
    // 按 channel_id 查的话另一个频道就永远是空的——实测 cctv11-MCP 就是这样
    // 一条节目都没有，而 CCTV1综合 有 25 条。
    `SELECT id, channel_id, xmltv_id, start_at, end_at, title, description, category
       FROM epg_programs
      WHERE xmltv_id = ? AND start_at >= ? AND start_at < ?
      ORDER BY start_at`,
  )
    .bind(ch.epg_id, start, end)
    .all<EpgRow>();

  return ok({
    // channel 的形状与 bootstrap 里的频道项一致，客户端两处都用同一个 DTO 解
    channel: {
      id: ch.id,
      number: ch.number,
      name: ch.name,
      group: ch.grp,
      logo: ch.logo,
      tvg_id: ch.tvg_id,
      epg_id: ch.epg_id,
      enabled: true,
      sort: ch.sort,
    },
    programs: (results ?? []).map((p) => ({
      id: p.id,
      epg_channel_id: p.channel_id,
      start_at: p.start_at,
      end_at: p.end_at,
      title: p.title,
      description: p.description,
      category: p.category,
    })),
    day,
    date,
    timezone: tz,
    server_time: now,
    day_start: start,
    day_end: end,
  });
}

// ---------------------------------------------------------------- 收藏 / 续播 / 频道列表
//
// 这四个接口以前在边缘版是「收下但不下发」：返回成功，数据直接丢掉。
// 现在用 D1 真的存起来了——收藏、跨端续播都不需要中转任何媒体字节，
// 没有理由不支持。

/** POST /api/v1/tv/favorite  { channel_id, on } */
async function tvFavorite(req: Request, env: Env, device: DeviceRow): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(1001, "参数错误：请求体不是合法 JSON");
  }
  const channelId = Number(body.channel_id ?? 0);
  if (!Number.isFinite(channelId) || channelId <= 0) {
    return fail(1001, "参数错误：channel_id 不能为空");
  }

  if (body.on === true) {
    await env.DB.prepare(
      "INSERT OR IGNORE INTO favorites (device_id, channel_id, created_at) VALUES (?,?,?)",
    )
      .bind(device.id, channelId, nowSec())
      .run();
  } else {
    await env.DB.prepare("DELETE FROM favorites WHERE device_id = ? AND channel_id = ?")
      .bind(device.id, channelId)
      .run();
  }
  return ok({ saved: true });
}

/** POST /api/v1/tv/progress  { channel_id, position_sec } */
async function tvSaveProgress(req: Request, env: Env, device: DeviceRow): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(1001, "参数错误：请求体不是合法 JSON");
  }
  const channelId = Number(body.channel_id ?? 0);
  if (!Number.isFinite(channelId) || channelId <= 0) {
    return fail(1001, "参数错误：channel_id 不能为空");
  }
  // 位置夹到非负：遥测类数据宁可夹也不报错，报错会让客户端整条链路静默失效
  const position = Math.max(0, Math.trunc(Number(body.position_sec ?? 0) || 0));

  await env.DB.prepare(
    `INSERT INTO watch_progress (device_id, channel_id, position_sec, updated_at)
     VALUES (?,?,?,?)
     ON CONFLICT(device_id, channel_id) DO UPDATE SET
       position_sec = excluded.position_sec,
       updated_at   = excluded.updated_at`,
  )
    .bind(device.id, channelId, position, nowSec())
    .run();

  return ok({ saved: true });
}

/**
 * GET /api/v1/tv/resume —— 「最近在**别的**设备上看过的频道」。
 *
 * 排除自己是有意的（与服务端一致）：自己刚看的那个台就在手边，
 * 提示「继续观看」没有意义，真正有用的是换台设备接着看。
 *
 * timeshift_available 恒为 0：边缘版没有时移，客户端据此只做「切到该台」，
 * 不能从偏移处起播。
 */
async function tvResume(env: Env, device: DeviceRow): Promise<Response> {
  const row = await env.DB.prepare(
    `SELECT w.channel_id,
            COALESCE(o.name, c.name) AS channel_name,
            COALESCE(o.logo, c.logo) AS channel_logo,
            COALESCE(o.group_name, c."group") AS grp,
            w.position_sec,
            w.updated_at,
            d.name AS device_name
       FROM watch_progress w
       JOIN channels c ON c.id = w.channel_id
       JOIN devices  d ON d.id = w.device_id
       LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key
      WHERE w.device_id <> ?
      ORDER BY w.updated_at DESC
      LIMIT 1`,
  )
    .bind(device.id)
    .first<{
      channel_id: number;
      channel_name: string;
      channel_logo: string;
      grp: string;
      position_sec: number;
      updated_at: number;
      device_name: string;
    }>();

  // 没有记录不是错误，返回空让客户端安静处理（与服务端一致）
  if (!row) return ok({ available: false });

  return ok({
    available: true,
    channel_id: row.channel_id,
    channel_name: row.channel_name,
    channel_logo: row.channel_logo,
    group: row.grp,
    position_sec: row.position_sec,
    device_name: row.device_name || "另一台设备",
    updated_at: row.updated_at,
    age_seconds: nowSec() - row.updated_at,
    timeshift_available: 0,
  });
}

/** GET /api/v1/tv/channels —— 分页频道列表（bootstrap 之外的第二条路） */
async function tvChannels(env: Env, url: URL): Promise<Response> {
  const group = (url.searchParams.get("group") || "").trim();
  const limit = Math.min(1000, Math.max(1, Number(url.searchParams.get("limit") || "500") || 500));
  const offset = Math.max(0, Number(url.searchParams.get("offset") || "0") || 0);

  const where = ["COALESCE(o.enabled, c.enabled) = 1"];
  const binds: unknown[] = [];
  if (group) {
    where.push(`COALESCE(o.group_name, c."group") = ?`);
    binds.push(group);
  }
  // 屏蔽清单（kv 里那一行）——客户端列表里不该出现被屏蔽的整类/同名频道
  where.push(`COALESCE(o.group_name, c."group") NOT IN (
                SELECT value FROM json_each(
                  CASE WHEN json_valid((SELECT v FROM kv WHERE k = 'blocked'))
                       THEN (SELECT v FROM kv WHERE k = 'blocked') ELSE '{}' END,
                  '$.groups'))`);
  where.push(`COALESCE(o.name, c.name) NOT IN (
                SELECT value FROM json_each(
                  CASE WHEN json_valid((SELECT v FROM kv WHERE k = 'blocked'))
                       THEN (SELECT v FROM kv WHERE k = 'blocked') ELSE '{}' END,
                  '$.channels'))`);
  const from = `FROM channels c
                  LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key
                 WHERE ${where.join(" AND ")}`;

  const total = await env.DB.prepare(`SELECT COUNT(*) AS n ${from}`)
    .bind(...binds)
    .first<{ n: number }>();

  const { results } = await env.DB.prepare(
    `SELECT c.id, c.number,
            COALESCE(o.name, c.name) AS name,
            COALESCE(o.group_name, c."group") AS grp,
            COALESCE(o.logo, c.logo) AS logo,
            c.tvg_id, c.epg_id, c.sort,
            (SELECT COUNT(*) FROM streams s WHERE s.channel_id = c.id) AS sc
       ${from}
     ORDER BY c.sort, c.number, c.id
     LIMIT ? OFFSET ?`,
  )
    .bind(...binds, limit, offset)
    .all<{
      id: number;
      number: number;
      name: string;
      grp: string;
      logo: string;
      tvg_id: string;
      epg_id: string;
      sort: number;
      sc: number;
    }>();

  return ok({
    total: total?.n ?? 0,
    items: (results ?? []).map((c) => ({
      id: c.id,
      number: c.number,
      name: c.name,
      group: c.grp,
      logo: c.logo,
      tvg_id: c.tvg_id,
      epg_id: c.epg_id,
      enabled: true,
      sort: c.sort,
      stream_count: c.sc,
      source_id: 0,
    })),
  });
}

/**
 * POST /api/v1/tv/heartbeat
 *
 * 除版本号外还顺手记录「上次在线 / App 版本」——管理台的设备列表要显示它。
 * 写入做了节流：心跳 30 秒一次，每次都写会吃掉 D1 的写入额度。
 * 条件不成立的 UPDATE 不产生写入行数，所以节流本身不花钱。
 */
async function tvHeartbeat(req: Request, env: Env, device: DeviceRow): Promise<Response> {
  let appVersion = "";
  try {
    const body = (await req.json()) as Record<string, unknown>;
    appVersion = String(body.app_version ?? "").slice(0, 32);
  } catch {
    // 心跳体不合法不该让心跳失败：客户端拿不到回包会以为掉线
  }
  const now = nowSec();

  // 写操作合并成一次 batch（一次往返），而且两条都带「条件不成立就不写」：
  // 心跳 30 秒一次，无脑 update 会把 D1 的每日写入额度吃光。
  //   1) last_seen 4 分钟内已经写过就不再写
  //      （bootstrap 已经不写了，这里是唯一更新点；在线判定是 300 秒，
  //       节流 240 秒保证「刚写过就仍判在线」不会误判离线）
  //   2) app_version 没变就不写
  const writes = [
    env.DB.prepare("UPDATE devices SET last_seen = ? WHERE id = ? AND last_seen < ?").bind(
      now,
      device.id,
      now - 240,
    ),
  ];
  if (appVersion) {
    writes.push(
      env.DB.prepare(
        `INSERT INTO device_state (device_id, app_version) VALUES (?,?)
         ON CONFLICT(device_id) DO UPDATE SET app_version = excluded.app_version
         WHERE device_state.app_version <> excluded.app_version`,
      ).bind(device.id, appVersion),
    );
  }
  await env.DB.batch(writes);

  // 两个版本号一次读回来：心跳是全站最高频的请求，少一次往返就少一次
  // 跨区域 RTT（D1 不在 Worker 本地）。
  const ver = await env.DB.prepare(
    `SELECT ${CHANNELS_VERSION_SQL} AS ch,
            COALESCE((SELECT config_version FROM device_state WHERE device_id = ?), 1) AS cfg`,
  )
    .bind(device.id)
    .first<{ ch: string; cfg: number }>();

  return ok({
    config_version: Number(ver?.cfg ?? 1),
    channels_version: ver?.ch ?? "0",
    server_time: now,
  });
}

// ---------------------------------------------------------------- 管理台

/**
 * 管理台口令。
 *
 * 优先用 ADMIN_PASSWORD；没配才退回 BIND_CODE（兼容老部署）。
 * 这两个值的用途本来完全不同：BIND_CODE 是给电视/手机绑定用的 6 位数字，
 * 会显示在用户眼前、被反复输入；拿它当管理台口令等于把管理权限交给
 * 一个只有 100 万种可能的短数字。一旦配置了 ADMIN_PASSWORD，
 * BIND_CODE 就不再能登录管理台。
 */
/**
 * 这个写操作会不会改变客户端看到的频道列表（决定要不要推进版本号）。
 *
 * 只按路径判断：改源、下发设备配置都不改频道表，推进版本号只会让所有电视
 * 多拉一次列表（一次 bootstrap 就是几百 KB + 一次重查询），没有任何好处。
 */
function affectsChannelList(path: string): boolean {
  if (path.startsWith("/api/v1/channels")) return true;
  if (path.startsWith("/api/v1/admin/channel")) return true;
  // 探活只改 streams.health，不影响列表；但它本身也是 POST
  if (path.startsWith("/api/v1/admin/stream") && !path.endsWith("/probe")) return true;
  return false;
}

/**
 * 管理台入口路径规范化。
 *
 * 允许部署者把管理台挂在一个自定义路径下（比如 `/litv-9f3a`），
 * 根路径和默认的 `/admin.html` 就都返回 404——扫到域名的人拿不到登录页。
 * 这不是强安全（API 仍在 `/api/` 下），但能挡掉碰运气的扫描，成本也几乎为零。
 *
 * 只接受 `[A-Za-z0-9_-]` 单段路径：多段和 `..`、`//`、百分号编码这些写法在
 * URL 规范化上各端理解不一致，是绕过检查的老路子。空值 = 不启用（兼容老部署）。
 */
function normalizeAdminPath(raw: unknown): string {
  const s = String(raw ?? "")
    .trim()
    .replace(/^\/+|\/+$/g, "");
  if (!s) return "";
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(s)) return "";
  return s;
}

/** 未配置入口时，这些路径都算「管理台首页」 */
const ADMIN_DEFAULT_ENTRIES = ["/", "/index.html", "/admin.html"];

/**
 * 管理台入口控制：返回 null 表示「按默认逻辑交给 assets」。
 *
 * 前提：`wrangler.toml` 里必须 `html_handling = "none"`，否则 assets 会把
 * `/` 自动映射到 index.html、把 `/admin.html` 307 到 `/admin`，这些都在
 * 跑 Worker **之前**完成，入口判断等于没有。这条在线上踩过。
 *
 * | 请求 | 未配 ADMIN_PATH | 配了 ADMIN_PATH |
 * |---|---|---|
 * | `/` | Vue 管理台 | **404** |
 * | `/{entry}` | — | Vue 管理台 |
 * | `/{entry}/simple` | — | 单文件备用台 |
 * | `/admin.html` | 单文件备用台 | **404** |
 *
 * 「Vue 管理台」= assets 里的 index.html（CI 把 admin/dist 拷进来的那份），
 * 功能比单文件版全，所以它是主入口。
 */
async function adminEntry(req: Request, env: Env, path: string): Promise<Response | null> {
  const entry = normalizeAdminPath(env.ADMIN_PATH);
  const pick = (target: string) =>
    env.ASSETS.fetch(new Request(new URL(target, req.url).toString(), req));

  if (!entry) {
    // 没配入口：根路径给 Vue 版，/admin.html 原样交给 assets（单文件备用台）。
    // 其余路径不动，保持老部署的行为。
    if (path === "/" || path === "/index.html") return pick("/index.html");
    return null;
  }

  const entryPath = "/" + entry;
  if (path === entryPath || path === entryPath + "/") return pick("/index.html");
  // 备用单文件管理台藏在入口下面，不占一个能猜到的公开路径
  if (path === entryPath + "/simple") return pick("/admin.html");
  if (ADMIN_DEFAULT_ENTRIES.includes(path)) {
    return new Response("Not Found", { status: 404 });
  }
  return null;
}

/**
 * 管理台口令：**只认 ADMIN_PASSWORD**。
 *
 * 以前是 `ADMIN_PASSWORD || BIND_CODE`——没配 ADMIN_PASSWORD 时退回绑定码。
 * 那等于把管理权限压在一个 6 位数字上，而且这个数字会显示在电视绑定页、
 * 被反复输入，还是家里每个人都知道的东西。用途本来就完全不同：
 *   BIND_CODE    → 给电视/手机绑定用，短、好输入、可以公开给家人
 *   ADMIN_PASSWORD → 管理台登录，长、保密、只有管理员知道
 *
 * 现在两者彻底分开：BIND_CODE 不能再登录管理台。没配 ADMIN_PASSWORD 时
 * 登录直接失败，并在界面上说清「去配 ADMIN_PASSWORD」——比"能用但很弱"安全。
 */
function adminPasscode(env: Env): string {
  return env.ADMIN_PASSWORD || "";
}

async function adminLogin(req: Request, env: Env): Promise<Response> {
  const passcode = adminPasscode(env);
  if (!passcode) return fail(2001, "服务端未配置管理台口令");

  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(1001, "请求体不是合法 JSON");
  }

  const ip = clientIp(req);
  const attempt = await bumpAttempt(env, `admin:${ip}`);
  if (attempt > RATE_MAX_FAILS) return fail(2005, "尝试次数过多，请 10 分钟后再试");

  // 自带管理台的登录页发的是 { password }，管理台简版发的是 { code }，两个都收。
  if (!safeEqual(String(body.code ?? body.password ?? ""), passcode)) {
    return fail(2001, "口令不正确");
  }
  await clearFailures(env, `admin:${ip}`);

  const now = nowSec();
  // 顺手清掉过期会话，不用单独排一个定时任务
  await env.DB.prepare("DELETE FROM admin_sessions WHERE expires_at < ?").bind(now).run();

  const token = randomToken();
  const expires = now + ADMIN_SESSION_SEC;
  await env.DB.prepare(
    "INSERT INTO admin_sessions (token_hash, created_at, expires_at) VALUES (?,?,?)",
  )
    .bind(await sha256hex(token), now, expires)
    .run();

  return ok({ token, expires_at: expires });
}

/**
 * 注销：删掉当前 bearer 对应的管理台会话。
 *
 * 以前这里是 `return ok(null)`——前端以为退出了，服务端会话其实还活着，
 * 7 天内拿到旧 token 依然能进管理台。注销必须真的删行。
 * 没有 token 也返回成功：注销是幂等操作，避免前端停在错误页。
 */
async function adminLogout(req: Request, env: Env): Promise<Response> {
  const token = bearer(req);
  if (!token) return ok(null);
  await env.DB.prepare("DELETE FROM admin_sessions WHERE token_hash = ?")
    .bind(await sha256hex(token))
    .run();
  return ok(null);
}

async function adminStats(env: Env): Promise<Response> {
  const row = await env.DB.prepare(
    // 「启用中」和「分组数」必须算上 channel_overrides：
    // 管理台隐藏一个订阅频道写的是 overrides，不是 channels.enabled，
    // 只数 channels 的话会显示「8 个频道 / 8 个启用中」，而列表里明明有一个是隐藏的。
    `SELECT
       (SELECT COUNT(*) FROM channels) AS channels,
       (SELECT COUNT(*) FROM channels c
          LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key
         WHERE COALESCE(o.enabled, c.enabled) = 1) AS enabled,
       (SELECT COUNT(*) FROM streams) AS streams,
       (SELECT COUNT(*) FROM (
          SELECT DISTINCT COALESCE(o.group_name, c."group") AS g
            FROM channels c
            LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key
        )) AS groups,
       (SELECT COUNT(*) FROM devices) AS devices,
       (SELECT COUNT(*) FROM channel_overrides) AS overrides`,
  ).first<Record<string, number>>();

  return ok(row ?? {});
}

async function adminChannels(env: Env, url: URL): Promise<Response> {
  const q = (url.searchParams.get("q") || "").trim();
  const group = (url.searchParams.get("group") || "").trim();
  const limit = Math.min(Math.max(Number(url.searchParams.get("limit") || "200"), 1), 500);

  const where: string[] = [];
  const binds: unknown[] = [];
  if (q) {
    where.push("(c.name LIKE ? OR c.tvg_id LIKE ?)");
    binds.push(`%${q}%`, `%${q}%`);
  }
  if (group) {
    where.push('COALESCE(o.group_name, c."group") = ?');
    binds.push(group);
  }
  // 屏蔽清单（kv 里一行 JSON）。管理台和房里的电视看到的必须是同一份列表——
  // 管理台有、电视上没有，用户只会以为同步坏了。规则本身在「屏蔽」页列着，随时能撤。
  where.push(`COALESCE(o.group_name, c."group") NOT IN (
                SELECT value FROM json_each(
                  CASE WHEN json_valid((SELECT v FROM kv WHERE k = 'blocked'))
                       THEN (SELECT v FROM kv WHERE k = 'blocked') ELSE '{}' END,
                  '$.groups'))`);
  where.push(`COALESCE(o.name, c.name) NOT IN (
                SELECT value FROM json_each(
                  CASE WHEN json_valid((SELECT v FROM kv WHERE k = 'blocked'))
                       THEN (SELECT v FROM kv WHERE k = 'blocked') ELSE '{}' END,
                  '$.channels'))`);

  const sql =
    `SELECT c.id, c.stable_key, c.number, c.origin,
            COALESCE(o.name, c.name) AS name,
            COALESCE(o.group_name, c."group") AS grp,
            COALESCE(o.logo, c.logo) AS logo,
            c.tvg_id,
            COALESCE(o.enabled, c.enabled) AS enabled,
            (SELECT COUNT(*) FROM streams s WHERE s.channel_id = c.id) AS streams
       FROM channels c
       LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key` +
    (where.length ? " WHERE " + where.join(" AND ") : "") +
    " ORDER BY c.sort, c.number, c.id LIMIT ?";

  const { results } = await env.DB.prepare(sql)
    .bind(...binds, limit)
    .all();

  const { results: groupRows } = await env.DB.prepare(
    // 别把别名起成 name：channels 自己就有 name 列，`GROUP BY name` 会直接
    // 报 "ambiguous column name"，整个频道页的接口 500。
    // 聚合放进子查询、外层再改名，语义清楚也不会歧义。
    `SELECT g AS name, n AS count FROM (
       SELECT COALESCE(o.group_name, c."group") AS g, COUNT(*) AS n
         FROM channels c
         LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key
        GROUP BY g
     ) ORDER BY count DESC`,
  ).all();

  return ok({ items: results ?? [], groups: groupRows ?? [] });
}

async function adminUpdateChannel(
  req: Request,
  env: Env,
  channelId: number,
): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(1001, "请求体不是合法 JSON");
  }

  const ch = await env.DB.prepare(
    "SELECT id, stable_key, origin FROM channels WHERE id = ?",
  )
    .bind(channelId)
    .first<{ id: number; stable_key: string; origin: string }>();
  if (!ch) return fail(2004, "频道不存在");

  const name = typeof body.name === "string" ? body.name.slice(0, 128) : null;
  const group = typeof body.group === "string" ? body.group.slice(0, 64) : null;
  const logo = typeof body.logo === "string" ? body.logo.slice(0, 512) : null;
  const enabled = typeof body.enabled === "boolean" ? (body.enabled ? 1 : 0) : null;

  if (ch.origin === "manual") {
    // 手工频道直接改本行，没有"重建冲掉"的问题
    await env.DB.prepare(
      `UPDATE channels SET
         name = COALESCE(?, name),
         "group" = COALESCE(?, "group"),
         logo = COALESCE(?, logo),
         enabled = COALESCE(?, enabled)
       WHERE id = ?`,
    )
      .bind(name, group, logo, enabled, channelId)
      .run();
  } else {
    // 订阅频道改到 overrides 表，下次重建仍然生效
    await env.DB.prepare(
      `INSERT INTO channel_overrides (stable_key, name, group_name, logo, enabled, updated_at)
       VALUES (?,?,?,?,?,?)
       ON CONFLICT(stable_key) DO UPDATE SET
         name       = COALESCE(excluded.name, channel_overrides.name),
         group_name = COALESCE(excluded.group_name, channel_overrides.group_name),
         logo       = COALESCE(excluded.logo, channel_overrides.logo),
         enabled    = COALESCE(excluded.enabled, channel_overrides.enabled),
         updated_at = excluded.updated_at`,
    )
      .bind(ch.stable_key, name, group, logo, enabled, nowSec())
      .run();
  }

  return ok(null);
}

async function adminDeleteChannel(env: Env, channelId: number): Promise<Response> {
  const ch = await env.DB.prepare(
    "SELECT id, stable_key, origin FROM channels WHERE id = ?",
  )
    .bind(channelId)
    .first<{ id: number; stable_key: string; origin: string }>();
  if (!ch) return fail(2004, "频道不存在");

  if (ch.origin === "manual") {
    await env.DB.batch([
      env.DB.prepare("DELETE FROM streams WHERE channel_id = ?").bind(channelId),
      env.DB.prepare("DELETE FROM channels WHERE id = ?").bind(channelId),
    ]);
  } else {
    // 订阅频道删不掉（下次重建还会回来），只能隐藏
    await env.DB.prepare(
      `INSERT INTO channel_overrides (stable_key, enabled, updated_at) VALUES (?, 0, ?)
       ON CONFLICT(stable_key) DO UPDATE SET enabled = 0, updated_at = excluded.updated_at`,
    )
      .bind(ch.stable_key, nowSec())
      .run();
  }

  return ok(null);
}

async function adminStreams(env: Env, url: URL): Promise<Response> {
  const channelId = Number(url.searchParams.get("channel_id") || "0");
  if (!channelId) return fail(1001, "缺少 channel_id");

  const { results } = await env.DB.prepare(
    `SELECT id, channel_id, label, quality, priority, health, latency_ms,
            url, ua, headers, origin
       FROM streams WHERE channel_id = ?
      ORDER BY priority DESC, id ASC`,
  )
    .bind(channelId)
    .all<StreamRow>();

  return ok((results ?? []).map((r) => ({ ...r, headers: parseHeaders(r.headers) })));
}

async function adminAddStream(req: Request, env: Env): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(1001, "请求体不是合法 JSON");
  }

  const channelId = Number(body.channel_id || 0);
  const rawUrl = String(body.url ?? "").trim();

  if (!channelId) return fail(1001, "缺少 channel_id");
  if (!/^https?:\/\//i.test(rawUrl)) return fail(1001, "地址必须以 http:// 或 https:// 开头");
  if (rawUrl.length > 2048) return fail(1001, "地址太长");

  const ch = await env.DB.prepare("SELECT stable_key FROM channels WHERE id = ?")
    .bind(channelId)
    .first<{ stable_key: string }>();
  if (!ch) return fail(2004, "频道不存在");

  const headers: Record<string, string> = {};
  const referer = String(body.referer ?? "").trim();
  if (referer) headers["Referer"] = referer;

  const res = await env.DB.prepare(
    `INSERT INTO streams (channel_id, channel_key, label, quality, priority, health, latency_ms, url, ua, headers, origin)
     VALUES (?,?,?,?,?,-1,0,?,?,?,'manual')`,
  )
    .bind(
      channelId,
      ch.stable_key,
      String(body.label ?? "手工线路").slice(0, 64),
      "",
      Number(body.priority ?? 0) || 0,
      rawUrl,
      String(body.ua ?? "").slice(0, 512),
      JSON.stringify(headers),
    )
    .run();

  return ok({ id: Number(res.meta.last_row_id) });
}

async function adminDeleteStream(env: Env, streamId: number): Promise<Response> {
  await env.DB.prepare("DELETE FROM streams WHERE id = ?").bind(streamId).run();
  return ok(null);
}

/**
 * 探活：从 Cloudflare 边缘发一个 Range 请求。
 *
 * 注意这个结果**不代表用户家里能播**——边缘的线路、DNS、组播可达性
 * 和家庭网络完全不同。只能当"这条源大概还活着"的参考。
 */
async function adminProbeStream(env: Env, streamId: number): Promise<Response> {
  const row = await env.DB.prepare(
    "SELECT id, url, ua, headers FROM streams WHERE id = ?",
  )
    .bind(streamId)
    .first<{ id: number; url: string; ua: string; headers: string }>();
  if (!row) return fail(2004, "线路不存在");

  const headers = parseHeaders(row.headers);
  if (row.ua && !Object.keys(headers).some((k) => k.toLowerCase() === "user-agent")) {
    headers["User-Agent"] = row.ua;
  }
  headers["Range"] = "bytes=0-1023";

  // 探活是「服务端替我发一个请求」，目标来自上游 m3u —— 不可信输入。
  // 必须拦本机/内网地址，并且跳转要逐跳校验（redirect: "follow" 会把刚校验过的
  // 地址绕过去）。规则与 CI 抓取共用 edge/src/net.ts。
  const bad = checkFetchTarget(row.url);
  if (bad) return fail(1001, `线路地址不可用：${bad}`);

  const t0 = Date.now();
  let healthy = 0;
  let status = 0;
  try {
    const res = await fetchGuarded(row.url, {
      method: "GET",
      headers,
      signal: AbortSignal.timeout(8000),
    });
    status = res.status;
    // 只要了前 1KB，别把整个流读进来
    res.body?.cancel().catch(() => {});
    healthy = res.ok || res.status === 206 ? 1 : 0;
  } catch {
    healthy = 0;
  }

  const latency = Date.now() - t0;
  await env.DB.prepare("UPDATE streams SET health = ?, latency_ms = ? WHERE id = ?")
    .bind(healthy, latency, streamId)
    .run();

  return ok({ health: healthy, latency_ms: latency, status });
}

async function adminDevices(env: Env): Promise<Response> {
  const { results } = await env.DB.prepare(
    "SELECT id, name, platform, device_key, created_at, last_seen FROM devices ORDER BY last_seen DESC",
  ).all();

  // device_key 是设备侧标识，管理台不需要看全，遮一半
  const items = (results ?? []).map((d) => ({
    ...d,
    device_key: String(d.device_key).slice(0, 8) + "…",
  }));

  return ok(items);
}

async function adminDeleteDevice(env: Env, deviceId: number): Promise<Response> {
  await env.DB.prepare("DELETE FROM devices WHERE id = ?").bind(deviceId).run();
  return ok(null);
}

// ---------------------------------------------------------------- 订阅源

interface SourceRow {
  id: number;
  name: string;
  url: string;
  ua: string;
  referer: string;
  enabled: number;
  sort: number;
  created_at: number;
  last_fetch_at: number;
  last_status: string;
  last_count: number;
}

async function adminSources(env: Env): Promise<Response> {
  const { results } = await env.DB.prepare(
    `SELECT id, name, url, ua, referer, enabled, sort, created_at,
            last_fetch_at, last_status, last_count
       FROM sources ORDER BY sort, id`,
  ).all<SourceRow>();
  return ok(results ?? []);
}

function readSourceBody(body: Record<string, unknown>) {
  const url = String(body.url ?? "").trim();
  const name = String(body.name ?? "").trim().slice(0, 64);
  const ua = String(body.ua ?? "").trim().slice(0, 512);
  const referer = String(body.referer ?? "").trim().slice(0, 512);
  const enabled = typeof body.enabled === "boolean" ? (body.enabled ? 1 : 0) : null;
  const sort = Number.isFinite(Number(body.sort)) ? Math.trunc(Number(body.sort)) : null;
  return { url, name, ua, referer, enabled, sort };
}

async function adminAddSource(req: Request, env: Env): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(1001, "请求体不是合法 JSON");
  }

  const f = readSourceBody(body);
  if (!/^https?:\/\//i.test(f.url)) return fail(1001, "地址必须以 http:// 或 https:// 开头");
  if (f.url.length > 2048) return fail(1001, "地址太长");

  const res = await env.DB.prepare(
    `INSERT INTO sources (name, url, ua, referer, enabled, sort, created_at)
     VALUES (?,?,?,?,?,?,?)`,
  )
    .bind(f.name, f.url, f.ua, f.referer, f.enabled ?? 1, f.sort ?? 0, nowSec())
    .run();

  return ok({ id: Number(res.meta.last_row_id) });
}

async function adminUpdateSource(req: Request, env: Env, id: number): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(1001, "请求体不是合法 JSON");
  }

  const exists = await env.DB.prepare("SELECT id FROM sources WHERE id = ?").bind(id).first();
  if (!exists) return fail(2004, "源不存在");

  const f = readSourceBody(body);
  if (f.url && !/^https?:\/\//i.test(f.url)) {
    return fail(1001, "地址必须以 http:// 或 https:// 开头");
  }
  if (f.url.length > 2048) return fail(1001, "地址太长");

  await env.DB.prepare(
    `UPDATE sources SET
       name    = COALESCE(NULLIF(?, ''), name),
       url     = COALESCE(NULLIF(?, ''), url),
       ua      = COALESCE(?, ua),
       referer = COALESCE(?, referer),
       enabled = COALESCE(?, enabled),
       sort    = COALESCE(?, sort)
     WHERE id = ?`,
  )
    .bind(
      f.name,
      f.url,
      // ua / referer：传了字符串就覆盖（**包括空串**，用来清空），不传则保持原值。
      // 注意配套：GET /sources 必须把 ua/referer 一起回给前端，
      // 否则编辑页表单是空的、一保存就传空串把配好的 UA 清掉。
      typeof body.ua === "string" ? f.ua : null,
      typeof body.referer === "string" ? f.referer : null,
      f.enabled,
      f.sort,
      id,
    )
    .run();

  return ok(null);
}

async function adminDeleteSource(env: Env, id: number): Promise<Response> {
  await env.DB.prepare("DELETE FROM sources WHERE id = ?").bind(id).run();
  return ok(null);
}

/**
 * 测试一个源：用这个源自己的 UA / Referer 拉一下，只读前 512 字节判断格式。
 *
 * 注意这是**边缘视角**，通了不代表你家能拉。只能当参考。
 */
async function adminTestSource(env: Env, id: number): Promise<Response> {
  const src = await env.DB.prepare(
    "SELECT id, url, ua, referer FROM sources WHERE id = ?",
  )
    .bind(id)
    .first<{ id: number; url: string; ua: string; referer: string }>();
  if (!src) return fail(2004, "源不存在");

  const headers: Record<string, string> = { Accept: "*/*" };
  if (src.ua) headers["User-Agent"] = src.ua;
  if (src.referer) headers["Referer"] = src.referer;

  // 同上：地址由用户在管理台填，属于不可信输入，先校验再发请求。
  const bad = checkFetchTarget(src.url);
  if (bad) return fail(1001, `源地址不可用：${bad}`);

  const t0 = Date.now();
  let status = 0;
  let format = "error";
  let sample = "";

  try {
    const res = await fetchGuarded(src.url, {
      headers,
      signal: AbortSignal.timeout(8000),
    });
    status = res.status;

    if (!res.ok) {
      format = `http_${res.status}`;
      res.body?.cancel().catch(() => {});
    } else {
      const reader = res.body?.getReader();
      if (reader) {
        const { value } = await reader.read();
        if (value) sample = new TextDecoder().decode(value.slice(0, 512));
        reader.cancel().catch(() => {});
      }
      const head = sample.trimStart();
      if (head.includes("#EXTM3U") || head.includes("#EXTINF")) format = "m3u";
      else if (head.includes("#genre#")) format = "txt";
      else if (head.startsWith("<")) format = "html";
      else if (head.startsWith("{") || head.startsWith("[")) format = "json";
      else if (!head) format = "empty";
      else format = "unknown";
    }
  } catch (e) {
    // 拦下来和被拒绝要分开报：前者是策略（别让用户去查网络），后者才是故障
    const msg = String((e as Error)?.message || "");
    format = msg.startsWith("blocked:") ? "blocked" : `error_${(e as Error).name}`;
  }

  return ok({ status, format, latency_ms: Date.now() - t0 });
}

// ---------------------------------------------------------------- 设置自省
//
// 为什么需要它：BIND_CODE 是 Secret，部署完就沉在 GitHub 里，
// 过几个月要加一台电视时没人记得那 6 位数字。管理台里能直接看到最省事——
// 反正要登录才拿得到。
//
// 默认**打码**（12•••56）：截图、投屏、旁边有人看的时候不至于直接暴露。
// 点「显示」才用 ?reveal=1 再要一次明文，两步操作的成本换来一个明确的动作边界。

/** 6 位绑定码打码成 `12•••56`；非 6 位（理论上不会有）只留最后两位 */
function maskCode(code: string): string {
  if (!code) return "";
  if (code.length <= 2) return "•".repeat(code.length);
  return code.slice(0, 2) + "•".repeat(Math.max(3, code.length - 4)) + code.slice(-2);
}

async function adminSettings(env: Env, url: URL): Promise<Response> {
  const bindCode = env.BIND_CODE || "";
  const reveal = url.searchParams.get("reveal") === "1";
  const entry = normalizeAdminPath(env.ADMIN_PATH);

  return ok({
    // reveal=1 时才给明文；其余情况只给打码版本
    bind_code: bindCode ? (reveal ? bindCode : maskCode(bindCode)) : "",
    bind_code_masked: maskCode(bindCode),
    // 绑定码没配 = 电视根本绑不上，界面要能直接指出来
    bind_code_set: Boolean(bindCode),
    // 管理台口令来自 ADMIN_PASSWORD（与绑定码彻底分开）
    admin_password_set: Boolean(env.ADMIN_PASSWORD),
    // 入口路径：空表示根路径就是入口
    admin_path: entry,
    admin_entry: entry ? `/${entry}` : "/",
  });
}

// ---------------------------------------------------------------- 重建

async function adminBuildInfo(env: Env): Promise<Response> {
  const row = await env.DB.prepare("SELECT v FROM kv WHERE k = 'last_build'").first<{ v: string }>();

  let last: unknown = null;
  try {
    last = row ? JSON.parse(row.v) : null;
  } catch {
    // 存坏了就当没有，不影响别的
  }

  return ok({
    last_build: last,
    // 只有「令牌 + 仓库」都齐了按钮才点得动——少一个就只在界面上说清缺什么，
    // 不要留一个点了没反应的按钮。
    can_rebuild: Boolean(env.GITHUB_DISPATCH_TOKEN) && Boolean(env.GITHUB_REPO),
    rebuild_target: {
      configured: Boolean(env.GITHUB_DISPATCH_TOKEN),
      repo: env.GITHUB_REPO || "",
      workflow: env.GITHUB_WORKFLOW || "edge-deploy.yml",
      ref: env.GITHUB_REF || "main",
    },
    // 必须和 .github/workflows/edge-deploy.yml 里的 cron 一致：
    // 那边是 `0 19 * * *`（UTC）= 北京时间 03:00，每天一次。
    // 曾经是每小时一次，但 D1 的写入额度是部署者自己的，而且 GitHub 的
    // cron 实测会整段跳过（真机上跳过过 6 小时），"每小时"的承诺是假的。
    schedule: "每天 03:00（北京时间，GitHub Actions 定时）",
  });
}

/**
 * 从管理台触发一次重建。
 *
 * 为什么是"触发 CI"而不是在 Worker 里直接拉取解析：免费版每次请求只有
 * 10ms CPU，解析一个像样的播放列表就超了。抓取和解析必须留在 Actions，
 * 这里只负责按一下按钮。
 *
 * 「点了没反应」的排查成本很高：GitHub 的 dispatch 接口成功只回 204、
 * 失败回 401/403/404/422，而这几个码背后的原因完全不同（token 过期、
 * 权限不够、仓库名写错、分支不存在）。所以这里把 GitHub 自己给的 message
 * 和"打的是哪个仓库/工作流/分支"一起带回管理台，不要只说"失败了"。
 */
async function adminRebuild(env: Env, method: string): Promise<Response> {
  const repo = env.GITHUB_REPO || "";
  const workflow = env.GITHUB_WORKFLOW || "edge-deploy.yml";
  const ref = env.GITHUB_REF || "main";
  const configured = Boolean(env.GITHUB_DISPATCH_TOKEN);

  // GET 用来做配置自检：管理台据此显示"点不了"的原因，而不是留个假按钮
  if (method === "GET") {
    return ok({ configured, repo, workflow, ref });
  }

  if (!configured) {
    return fail(
      2006,
      // ★ 这里必须写 GitHub 上的 Secret 名（DISPATCH_TOKEN），不能写 Worker 里的
      // 环境变量名（GITHUB_DISPATCH_TOKEN）：GitHub 拒绝以 GITHUB_ 开头的 secret 名，
      // 用户照着 Worker 的名字去建会直接被界面拦住（"Secret names must not start
      // with GITHUB_"）。两边名字不同是刻意的，不是写错。
      "没有配置 DISPATCH_TOKEN，管理台不能触发重建。" +
        "去仓库 Settings → Secrets 里新建一个叫 DISPATCH_TOKEN 的 secret" +
        "（GitHub 不允许 secret 名以 GITHUB_ 开头，所以它不叫 GITHUB_DISPATCH_TOKEN）。" +
        "没配也可以去 Actions 手动跑，或等每天 03:00 的定时任务。",
    );
  }
  if (!repo) {
    return fail(
      2006,
      "没有配置 GITHUB_REPO，不知道要触发哪个仓库的重建。" +
        "正常部署时 CI 会自动写入这个变量；手工部署的话在 wrangler.toml 的 [vars] 里设。",
    );
  }

  const res = await fetch(
    `https://api.github.com/repos/${repo}/actions/workflows/${workflow}/dispatches`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${env.GITHUB_DISPATCH_TOKEN}`,
        Accept: "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "litv-edge",
        "content-type": "application/json",
      },
      body: JSON.stringify({ ref }),
    },
  );

  // 成功是 204 无正文；失败带一段 JSON，里面的 message 就是最准确的线索
  const bodyText = await res.text().catch(() => "");
  if (res.status === 204 || res.status === 201) return ok({ dispatched: true });

  let why = "";
  try {
    why = String((JSON.parse(bodyText) as { message?: string }).message ?? "");
  } catch {
    /* 不是 JSON 就忽略 */
  }

  const target = `${repo} · ${workflow} · ${ref}`;
  const hint =
    res.status === 401
      ? "DISPATCH_TOKEN 无效或已过期（在仓库 Settings → Secrets 里重新生成）"
      : res.status === 403
        ? "令牌权限不够：fine-grained PAT 要为这个仓库勾上 Actions: write"
        : res.status === 404
          ? `仓库或工作流不存在，或者令牌没有这个仓库的权限（目标是 ${target}）`
          : res.status === 422
            ? `分支 ${ref} 不存在（GITHUB_REF 要和默认分支一致）`
            : "GitHub 拒绝了触发请求";

  return fail(2006, `${hint}。HTTP ${res.status}${why ? `：${why}` : ""}`);
}

// ---------------------------------------------------------------- 屏蔽清单
//
// 存在 kv 的 `blocked` 一行里（不建表：D1 免费额度按行计费，
// 这份清单只在改配置时变，多一张表就多一次查询、多一份迁移要维护）。
// 形如 {"groups":["购物"],"channels":["测试台"]}。

interface Blocked {
  groups: string[];
  channels: string[];
}

function normalizeBlocked(raw: unknown): Blocked {
  const o = (raw ?? {}) as Record<string, unknown>;
  const list = (v: unknown) =>
    Array.isArray(v) ? [...new Set(v.map((x) => String(x).trim()).filter(Boolean))].slice(0, 500) : [];
  return { groups: list(o.groups), channels: list(o.channels) };
}

async function readBlocked(env: Env): Promise<Blocked> {
  const row = await env.DB.prepare("SELECT v FROM kv WHERE k = 'blocked'").first<{ v: string }>();
  try {
    return normalizeBlocked(JSON.parse(row?.v || "{}"));
  } catch {
    // 手动改坏了也不能让管理台/客户端一起崩，当成"没屏蔽"
    return { groups: [], channels: [] };
  }
}

async function saveBlocked(env: Env, b: Blocked): Promise<void> {
  await env.DB.prepare(
    "INSERT INTO kv (k, v) VALUES ('blocked', ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v",
  )
    .bind(JSON.stringify(b))
    .run();
  // 列表变了，电视那边要靠这个指纹才会重拉
  await bumpChannelsVersion(env);
}

async function adminAddBlocked(req: Request, env: Env): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(1001, "参数错误：请求体不是合法 JSON");
  }
  const kind = String(body.kind ?? "");
  const value = String(body.value ?? "").trim().slice(0, 64);
  if (kind !== "group" && kind !== "channel") return fail(1001, "参数错误：kind 只能是 group 或 channel");
  if (!value) return fail(1001, "参数错误：要屏蔽的名字不能为空");

  const cur = await readBlocked(env);
  const key = kind === "group" ? "groups" : "channels";
  if (!cur[key].includes(value)) cur[key] = [...cur[key], value];
  await saveBlocked(env, cur);
  return ok(cur);
}

/**
 * 屏蔽页要的数据：**一条**查询同时拿回清单和候选分类。
 *
 * 为什么候选要单独算：`channels` 的普通查询已经被屏蔽规则过滤过了，
 * 拿它当候选就会「屏蔽完这一行就消失了」，再也没法解除。
 * 这里用原始统计（不过滤）+ 把已屏蔽但源里已经没有的分类补上——
 * 否则重建后进度条归零，用户在界面上再也找不到它。
 *
 * 合成一条 SQL 是为了不多花 D1 查询（免费额度按查询/行算）。
 */
async function adminBlockedInfo(env: Env): Promise<Response> {
  const row = await env.DB.prepare(
    `SELECT
       (SELECT v FROM kv WHERE k = 'blocked') AS blocked,
       (SELECT json_group_array(json_object('name', name, 'count', n)) FROM (
          -- 内层别名必须是 g：channels 自己有 name 列，写成 AS name 再
          -- GROUP BY name 会直接报 "ambiguous column name"（踩过一次了）。
          SELECT g AS name, SUM(n) AS n FROM (
            -- 只统计「会下发的」频道（算上 overrides 的隐藏），
            -- 这样这个数字和用户点进去看到的列表条数一致；
            -- 用 COUNT(*) 不过滤的话，明明列表里有 2 个台却显示 3，会让人怀疑是不是漏屏蔽了。
            SELECT COALESCE(o.group_name, c."group") AS g, COUNT(*) AS n
              FROM channels c
              LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key
             WHERE COALESCE(o.enabled, c.enabled) = 1
             GROUP BY g
            UNION ALL
            -- 已屏蔽、但重建后已经不入库的分类：补一条 count 0，
            -- 保证它一直留在界面上，能解除
            SELECT value, 0 FROM json_each(
              CASE WHEN json_valid((SELECT v FROM kv WHERE k = 'blocked'))
                   THEN (SELECT v FROM kv WHERE k = 'blocked') ELSE '{}' END,
              '$.groups')
          )
          GROUP BY g
          ORDER BY n DESC, g ASC
       )) AS groups`,
  ).first<{ blocked: string | null; groups: string | null }>();

  let blocked: Blocked;
  try {
    blocked = normalizeBlocked(JSON.parse(row?.blocked || "{}"));
  } catch {
    blocked = { groups: [], channels: [] };
  }

  let candidates: { name: string; count: number }[] = [];
  try {
    const parsed = JSON.parse(row?.groups || "[]");
    if (Array.isArray(parsed)) {
      candidates = parsed
        .map((g) => ({ name: String(g.name ?? ""), count: Number(g.count) || 0 }))
        .filter((g) => g.name);
    }
  } catch {
    /* 拼坏了就当没有候选，别让整页崩 */
  }

  const blockedSet = new Set(blocked.groups);
  return ok({
    groups: blocked.groups,
    channels: blocked.channels,
    // 源里读到的分类，每项带频道数；blocked 由前端按 groups 对照
    candidates: candidates.map((c) => ({ ...c, blocked: blockedSet.has(c.name) })),
  });
}

async function adminDelBlocked(env: Env, url: URL): Promise<Response> {
  const kind = url.searchParams.get("kind") ?? "";
  const value = (url.searchParams.get("value") ?? "").trim();
  if (kind !== "group" && kind !== "channel") return fail(1001, "参数错误：kind 只能是 group 或 channel");
  if (!value) return fail(1001, "参数错误：要解除的名字不能为空");

  const cur = await readBlocked(env);
  const key = kind === "group" ? "groups" : "channels";
  cur[key] = cur[key].filter((x) => x !== value);
  await saveBlocked(env, cur);
  return ok(cur);
}

// ---------------------------------------------------------------- 自带管理台兼容层
//
// LITV2 自带的管理台（admin/ 的 Vue）调的是 /api/v1/{sources,channels,devices,system,epg}，
// 数据形状见 admin/src/api/types.ts。这里按那套形状回话，让它能直接跑。
//
// 原则：能真做的真做，做不到的**明确报错**。返回假数据会让人以为配置生效了，
// 比报错更糟。

const EDGE_ME = { uid: "edge", username: "admin", is_admin: true };

function isoOrNull(sec: number): string | null {
  return sec > 0 ? new Date(sec * 1000).toISOString() : null;
}

function notSupported(what: string): Response {
  return fail(2006, `边缘模式不支持${what}`);
}

function toCompatSource(r: SourceRow) {
  const good = r.last_status === "ok";
  return {
    id: r.id,
    name: r.name || `源 ${r.id}`,
    type: "m3u",
    url: r.url,
    enabled: r.enabled === 1,
    status: r.last_status === "" ? "never" : good ? "ok" : "error",
    last_fetch_at: isoOrNull(r.last_fetch_at),
    error: r.last_status === "" || good ? null : r.last_status,
    channel_count: r.last_count,
    // Vue 管理台读 `user_agent`，备用台（admin.html）老代码读 `ua`。
    // 两个都给：少给一个，另一边编辑源时表单是空的，一保存就把配好的 UA 清掉——
    // 「我明明配了 UA，客户端却收不到」就是这么来的。
    user_agent: r.ua,
    ua: r.ua,
    referer: r.referer,
  };
}

// ---- sources ----

async function compatSources(env: Env): Promise<Response> {
  const { results } = await env.DB.prepare(
    `SELECT id, name, url, ua, referer, enabled, sort, created_at,
            last_fetch_at, last_status, last_count
       FROM sources ORDER BY sort, id`,
  ).all<SourceRow>();
  return ok((results ?? []).map(toCompatSource));
}

async function compatAddSource(req: Request, env: Env): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(1001, "请求体不是合法 JSON");
  }

  const url = String(body.url ?? "").trim();
  if (!/^https?:\/\//i.test(url)) return fail(1001, "地址必须以 http:// 或 https:// 开头");
  if (url.length > 2048) return fail(1001, "地址太长");

  const res = await env.DB.prepare(
    `INSERT INTO sources (name, type, url, ua, referer, enabled, sort, created_at)
     VALUES (?,?,?,?,?,?,?,?)`,
  )
    .bind(
      String(body.name ?? "").slice(0, 64),
      "m3u",
      url,
      String(body.user_agent ?? "").slice(0, 512),
      // 有些面板不认默认 Referer 就 403，正式台的表单也填它。
      // 以前这里写死空串，等于管理台存进去的 Referer 每次新建都被丢掉。
      String(body.referer ?? "").slice(0, 512),
      body.enabled === false ? 0 : 1,
      Number(body.sort ?? 0) || 0,
      nowSec(),
    )
    .run();

  const row = await env.DB.prepare(
    `SELECT id, name, url, ua, referer, enabled, sort, created_at,
            last_fetch_at, last_status, last_count FROM sources WHERE id = ?`,
  )
    .bind(Number(res.meta.last_row_id))
    .first<SourceRow>();

  return ok(row ? toCompatSource(row) : null);
}

async function compatUpdateSource(req: Request, env: Env, id: number): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(1001, "请求体不是合法 JSON");
  }

  const url = String(body.url ?? "").trim();
  if (url && !/^https?:\/\//i.test(url)) return fail(1001, "地址必须以 http:// 或 https:// 开头");

  await env.DB.prepare(
    `UPDATE sources SET
       name    = COALESCE(NULLIF(?, ''), name),
       url     = COALESCE(NULLIF(?, ''), url),
       ua      = COALESCE(?, ua),
       referer = COALESCE(?, referer),
       enabled = COALESCE(?, enabled)
     WHERE id = ?`,
  )
    .bind(
      String(body.name ?? "").slice(0, 64),
      url.slice(0, 2048),
      typeof body.user_agent === "string" ? String(body.user_agent).slice(0, 512) : null,
      // 传了才覆盖，没传保持原值（和 ua 同一口径）
      typeof body.referer === "string" ? String(body.referer).slice(0, 512) : null,
      typeof body.enabled === "boolean" ? (body.enabled ? 1 : 0) : null,
      id,
    )
    .run();

  const row = await env.DB.prepare(
    `SELECT id, name, url, ua, referer, enabled, sort, created_at,
            last_fetch_at, last_status, last_count FROM sources WHERE id = ?`,
  )
    .bind(id)
    .first<SourceRow>();

  return row ? ok(toCompatSource(row)) : fail(2004, "源不存在");
}

// ---- channels ----

interface ChannelBrief {
  id: number;
  stable_key: string;
  origin: string;
}

async function loadChannel(env: Env, id: number): Promise<ChannelBrief | null> {
  return env.DB.prepare("SELECT id, stable_key, origin FROM channels WHERE id = ?")
    .bind(id)
    .first<ChannelBrief>();
}

/** 对订阅频道的人工修改：一个 upsert，多个调用点共用 */
function overrideStmt(
  env: Env,
  stableKey: string,
  f: { name?: string | null; group?: string | null; enabled?: number | null },
  now = nowSec(),
) {
  return env.DB.prepare(
    `INSERT INTO channel_overrides (stable_key, name, group_name, enabled, updated_at)
     VALUES (?,?,?,?,?)
     ON CONFLICT(stable_key) DO UPDATE SET
       name       = COALESCE(excluded.name, channel_overrides.name),
       group_name = COALESCE(excluded.group_name, channel_overrides.group_name),
       enabled    = COALESCE(excluded.enabled, channel_overrides.enabled),
       updated_at = excluded.updated_at`,
  ).bind(stableKey, f.name ?? null, f.group ?? null, f.enabled ?? null, now);
}

/** 改一个频道的字段。订阅频道写 overrides，手工频道直接改本行。 */
async function setChannelFields(
  env: Env,
  ch: ChannelBrief,
  f: { name?: string | null; group?: string | null; enabled?: number | null; number?: number | null },
): Promise<void> {
  if (ch.origin === "manual") {
    await env.DB.prepare(
      `UPDATE channels SET
         name    = COALESCE(?, name),
         "group" = COALESCE(?, "group"),
         enabled = COALESCE(?, enabled),
         number  = COALESCE(?, number)
       WHERE id = ?`,
    )
      .bind(f.name ?? null, f.group ?? null, f.enabled ?? null, f.number ?? null, ch.id)
      .run();
  } else {
    await overrideStmt(env, ch.stable_key, f).run();
  }
}

async function compatChannels(env: Env, url: URL): Promise<Response> {
  const q = (url.searchParams.get("q") || "").trim();
  const group = (url.searchParams.get("group") || "").trim();
  const page = Math.max(1, Number(url.searchParams.get("page") || "1") || 1);
  const size = Math.min(200, Math.max(1, Number(url.searchParams.get("size") || "20") || 20));

  const where: string[] = [];
  const binds: unknown[] = [];
  if (q) {
    where.push("(c.name LIKE ? OR c.tvg_id LIKE ?)");
    binds.push(`%${q}%`, `%${q}%`);
  }
  if (group) {
    where.push('COALESCE(o.group_name, c."group") = ?');
    binds.push(group);
  }
  // 屏蔽清单（kv 里一行 JSON）。管理台和房里的电视看到的必须是同一份列表——
  // 管理台有、电视上没有，用户只会以为同步坏了。规则本身在「屏蔽」页列着，随时能撤。
  where.push(`COALESCE(o.group_name, c."group") NOT IN (
                SELECT value FROM json_each(
                  CASE WHEN json_valid((SELECT v FROM kv WHERE k = 'blocked'))
                       THEN (SELECT v FROM kv WHERE k = 'blocked') ELSE '{}' END,
                  '$.groups'))`);
  where.push(`COALESCE(o.name, c.name) NOT IN (
                SELECT value FROM json_each(
                  CASE WHEN json_valid((SELECT v FROM kv WHERE k = 'blocked'))
                       THEN (SELECT v FROM kv WHERE k = 'blocked') ELSE '{}' END,
                  '$.channels'))`);

  const from =
    "FROM channels c LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key" +
    (where.length ? " WHERE " + where.join(" AND ") : "");

  const total = await env.DB.prepare(`SELECT COUNT(*) AS n ${from}`)
    .bind(...binds)
    .first<{ n: number }>();

  const { results } = await env.DB.prepare(
    `SELECT c.id, c.number,
            COALESCE(o.name, c.name) AS name,
            COALESCE(o.group_name, c."group") AS grp,
            COALESCE(o.logo, c.logo) AS logo,
            c.tvg_id, c.epg_id,
            COALESCE(o.enabled, c.enabled) AS enabled,
            (SELECT COUNT(*) FROM streams s WHERE s.channel_id = c.id) AS stream_count
       ${from}
      ORDER BY c.sort, c.number, c.id
      LIMIT ? OFFSET ?`,
  )
    .bind(...binds, size, (page - 1) * size)
    .all<{
      id: number;
      number: number;
      name: string;
      grp: string;
      logo: string;
      tvg_id: string;
      epg_id: string;
      enabled: number;
      stream_count: number;
    }>();

  return ok({
    total: total?.n ?? 0,
    items: (results ?? []).map((r) => ({
      id: r.id,
      number: r.number,
      name: r.name,
      group: r.grp,
      logo: r.logo,
      tvg_id: r.tvg_id,
      epg_id: r.epg_id || null,
      enabled: r.enabled === 1,
      stream_count: r.stream_count,
    })),
  });
}

async function compatGroups(env: Env): Promise<Response> {
  const { results } = await env.DB.prepare(
    // 同 compatChannels：别名不能叫 name，会和 channels.name 撞成
    // "ambiguous column name"。
    `SELECT g AS name, n AS count FROM (
       SELECT COALESCE(o.group_name, c."group") AS g, COUNT(*) AS n
         FROM channels c
         LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key
        GROUP BY g
     ) ORDER BY count DESC, name ASC`,
  ).all<{ name: string; count: number }>();
  return ok(results ?? []);
}

async function compatCreateChannel(req: Request, env: Env): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(1001, "请求体不是合法 JSON");
  }

  const name = String(body.name ?? "").trim();
  if (!name) return fail(1001, "频道名不能为空");

  const res = await env.DB.prepare(
    `INSERT INTO channels (stable_key, number, name, "group", logo, tvg_id, epg_id, enabled, sort, origin)
     VALUES (?,?,?,?,?,?,?,?,?, 'manual')`,
  )
    .bind(
      `manual:${crypto.randomUUID()}`,
      Number(body.number ?? 0) || 0,
      name.slice(0, 128),
      String(body.group ?? "未分组").slice(0, 64) || "未分组",
      String(body.logo ?? "").slice(0, 512),
      String(body.tvg_id ?? "").slice(0, 128),
      String(body.epg_id ?? "").slice(0, 128),
      body.enabled === false ? 0 : 1,
      0,
    )
    .run();

  const id = Number(res.meta.last_row_id);
  return ok({
    id,
    number: Number(body.number ?? 0) || 0,
    name,
    group: String(body.group ?? "未分组"),
    logo: String(body.logo ?? ""),
    tvg_id: String(body.tvg_id ?? ""),
    epg_id: String(body.epg_id ?? "") || null,
    enabled: body.enabled !== false,
    stream_count: 0,
  });
}

async function compatBatch(req: Request, env: Env): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(1001, "请求体不是合法 JSON");
  }

  const ids = Array.isArray(body.ids) ? body.ids.map(Number).filter((n) => n > 0) : [];
  const action = String(body.action ?? "");
  const value = typeof body.value === "string" ? body.value : "";

  if (ids.length === 0) return fail(1001, "没有选中频道");
  if (ids.length > 500) return fail(1001, "一次最多 500 个");

  // 先把 action 校验完再动手：以前是在循环里遇到不认识的操作才 fail，
  // 前面几个频道已经改完了——「要么全改要不一个不改」才配得上批量操作。
  if (!["delete", "enable", "disable", "set_group"].includes(action)) {
    return fail(1001, `不认识的操作：${action}`);
  }
  if (action === "set_group" && !value) return fail(1001, "缺少目标分组");

  // 免费版 50 subrequest/请求是硬上限：原来每个 id 两次往返，
  // 500 个就是 1000 次。现在查询按 50 个一批（IN 的绑定参数上限也是 100），
  // 写全部攒起来走 batch（1 次往返），事务语义由 D1 的 batch 保证。
  const now = nowSec();
  const stmts: D1PreparedStatement[] = [];

  for (let i = 0; i < ids.length; i += 50) {
    const chunk = ids.slice(i, i + 50);
    const { results } = await env.DB.prepare(
      `SELECT id, stable_key, origin FROM channels
        WHERE id IN (${chunk.map(() => "?").join(",")})`,
    )
      .bind(...chunk)
      .all<ChannelBrief>();

    for (const ch of results ?? []) {
      if (action === "delete" && ch.origin === "manual") {
        stmts.push(env.DB.prepare("DELETE FROM streams WHERE channel_id = ?").bind(ch.id));
        stmts.push(env.DB.prepare("DELETE FROM channels WHERE id = ?").bind(ch.id));
        continue;
      }
      const f =
        action === "delete" || action === "disable"
          ? { enabled: 0 }
          : action === "enable"
            ? { enabled: 1 }
            : { group: value.slice(0, 64) };
      // 订阅频道不能真删（下次重建还会回来），"删除"对它是隐藏
      stmts.push(overrideStmt(env, ch.stable_key, f, now));
    }
  }

  // batch 一次能带的语句数没有正式承诺，攒太多就不分块地整块发容易顶到上限
  for (let i = 0; i < stmts.length; i += 100) {
    await env.DB.batch(stmts.slice(i, i + 100));
  }
  return ok(null);
}

async function compatReorder(req: Request, env: Env): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(1001, "请求体不是合法 JSON");
  }

  const ids = Array.isArray(body.ids) ? body.ids.map(Number).filter((n) => n > 0) : [];
  if (ids.length === 0) return fail(1001, "没有选中频道");
  if (ids.length > 500) return fail(1001, "一次最多 500 个");

  // sort 直接写成新顺序的下标。批量操作走事务，避免改一半。
  const stmts = ids.map((id, i) =>
    env.DB.prepare("UPDATE channels SET sort = ? WHERE id = ?").bind(i, id),
  );
  await env.DB.batch(stmts);

  return ok(null);
}

async function compatStreams(env: Env, channelId: number): Promise<Response> {
  const { results } = await env.DB.prepare(
    `SELECT id, url, label, quality, latency_ms
       FROM streams WHERE channel_id = ?
      ORDER BY priority DESC, id ASC`,
  )
    .bind(channelId)
    .all<{ id: number; url: string; label: string; quality: string; latency_ms: number }>();

  return ok(
    (results ?? []).map((r) => ({
      id: r.id,
      url: r.url,
      quality: r.quality || r.label,
      enabled: true,
      latency_ms: r.latency_ms,
    })),
  );
}

async function compatAddStream(req: Request, env: Env, channelId: number): Promise<Response> {
  let body: Record<string, unknown>;
  try {
    body = (await req.json()) as Record<string, unknown>;
  } catch {
    return fail(1001, "请求体不是合法 JSON");
  }

  const url = String(body.url ?? "").trim();
  if (!/^https?:\/\//i.test(url)) return fail(1001, "地址必须以 http:// 或 https:// 开头");

  const ch = await loadChannel(env, channelId);
  if (!ch) return fail(2004, "频道不存在");

  const res = await env.DB.prepare(
    `INSERT INTO streams (channel_id, channel_key, label, quality, priority, health, latency_ms, url, ua, headers, origin)
     VALUES (?,?,?,'',0,-1,0,?,'','{}','manual')`,
  )
    .bind(channelId, ch.stable_key, "手工线路", url.slice(0, 2048))
    .run();

  return ok({ id: Number(res.meta.last_row_id), url, quality: "手工线路", enabled: true, latency_ms: 0 });
}

// ---- devices ----

async function compatDevices(env: Env, url: URL): Promise<Response> {
  // 边缘没有"待批准"流程：绑定码对了就是 bound
  if (url.searchParams.get("status") === "pending") return ok([]);

  const { results } = await env.DB.prepare(
    `SELECT d.id, d.name, d.platform, d.last_seen,
            COALESCE(s.app_version, '') AS app_version,
            s.config_json
       FROM devices d
       LEFT JOIN device_state s ON s.device_id = d.id
      ORDER BY d.last_seen DESC`,
  ).all<{
    id: number;
    name: string;
    platform: string;
    last_seen: number;
    app_version: string;
    config_json: string | null;
  }>();

  const now = nowSec();
  return ok(
    (results ?? []).map((d) => ({
      id: d.id,
      name: d.name || "未命名",
      platform: d.platform,
      model: "",
      online: now - d.last_seen < 300,
      last_heartbeat_at: isoOrNull(d.last_seen),
      status: "bound",
      ip: "",
      app_version: d.app_version,
      // 没下发过配置就给默认值：客户端与管理台都按「完整对象」处理
      config: parseConfigOr(d.config_json, DEFAULT_DEVICE_CONFIG),
    })),
  );
}

/** 把库里存的配置字符串解析成对象；坏数据一律回落默认值 */
function parseConfigOr(raw: string | null, fallbackJson: string): unknown {
  const text = raw && raw.trim() ? raw : fallbackJson;
  try {
    return JSON.parse(text);
  } catch {
    return JSON.parse(fallbackJson);
  }
}

/**
 * PUT /api/v1/devices/{id}/config —— 管理台「下发配置」。
 *
 * 只做一件事：把配置存起来并推进 config_version。客户端下一次心跳发现
 * 版本变了就重新拉 bootstrap 生效——不需要审批流程，也不需要长连接，
 * 所以这项在边缘架构里是**做得成**的（README 以前把它和审批一起列为不支持）。
 */
async function compatDeviceConfig(req: Request, env: Env, deviceId: number): Promise<Response> {
  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return fail(1001, "参数错误：请求体不是合法 JSON");
  }
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    return fail(1001, "参数错误：配置必须是一个对象");
  }

  const exists = await env.DB.prepare("SELECT id FROM devices WHERE id = ?").bind(deviceId).first();
  if (!exists) return fail(2004, "设备不存在");

  // 与默认值合并后再存：客户端读的是完整对象，缺字段会拿到 undefined。
  // 存之前序列化一次，保证库里永远是合法 JSON——bootstrap 用 json() 内联它，
  // 一段坏 JSON 会让整个 bootstrap 报错。
  const merged = { ...JSON.parse(DEFAULT_DEVICE_CONFIG), ...(body as Record<string, unknown>) };
  const json = JSON.stringify(merged);

  await env.DB.prepare(
    `INSERT INTO device_state (device_id, config_json, config_version) VALUES (?,?,2)
     ON CONFLICT(device_id) DO UPDATE SET
       config_json    = excluded.config_json,
       config_version = device_state.config_version + 1`,
  )
    .bind(deviceId, json)
    .run();

  return ok({ saved: true, config: merged });
}

// ---- system ----

async function compatSystemInfo(env: Env): Promise<Response> {
  const row = await env.DB.prepare(
    `SELECT
       (SELECT COUNT(*) FROM channels WHERE enabled = 1) AS channels_total,
       (SELECT COUNT(*) FROM devices WHERE last_seen > ?) AS devices_online,
       (SELECT COUNT(*) FROM sources WHERE last_status NOT IN ('', 'ok')) AS sources_failed`,
  )
    .bind(nowSec() - 300)
    .first<{ channels_total: number; devices_online: number; sources_failed: number }>();

  return ok({
    version: "edge",
    arch: "cloudflare-workers",
    channels_total: row?.channels_total ?? 0,
    devices_online: row?.devices_online ?? 0,
    sources_failed: row?.sources_failed ?? 0,
  });
}

// ---- 路由分发 ----

async function compatAdmin(
  req: Request,
  env: Env,
  path: string,
  method: string,
  url: URL,
): Promise<Response | null> {
  // 屏蔽清单（边缘版独有：整类 / 同名频道不下发）。
  // 自带管理台的「屏蔽」页走这里；逻辑和 /api/v1/admin/blocked 共用同一批函数，
  // 只是路径前缀不同（Vue 那边统一走 api/v1/）。
  if (path === "/api/v1/blocked") {
    if (method === "POST") return adminAddBlocked(req, env);
    if (method === "DELETE") return adminDelBlocked(env, url);
    return adminBlockedInfo(env);
  }

  // sources
  if (path === "/api/v1/sources") {
    if (method === "POST") return compatAddSource(req, env);
    return compatSources(env);
  }
  const srcId = /^\/api\/v1\/sources\/(\d+)$/.exec(path);
  if (srcId) {
    const id = Number(srcId[1]);
    if (method === "PUT") return compatUpdateSource(req, env, id);
    if (method === "DELETE") {
      await env.DB.prepare("DELETE FROM sources WHERE id = ?").bind(id).run();
      return ok(null);
    }
  }
  if (/^\/api\/v1\/sources\/\d+\/(refresh|preview)$/.test(path)) {
    // 拉取 + 解析会超免费版的 10ms CPU，只能交给 CI
    return notSupported("在页面里拉取源（请点「立即重建」，抓取和解析在 CI 里做）");
  }

  // channels
  if (path === "/api/v1/channels") {
    if (method === "POST") return compatCreateChannel(req, env);
    return compatChannels(env, url);
  }
  if (path === "/api/v1/channels/batch" && method === "POST") return compatBatch(req, env);
  if (path === "/api/v1/channels/reorder" && method === "POST") return compatReorder(req, env);
  if (path === "/api/v1/groups") return compatGroups(env);

  const chId = /^\/api\/v1\/channels\/(\d+)$/.exec(path);
  if (chId) {
    const id = Number(chId[1]);
    const ch = await loadChannel(env, id);
    if (!ch) return fail(2004, "频道不存在");

    if (method === "DELETE") {
      if (ch.origin === "manual") {
        await env.DB.batch([
          env.DB.prepare("DELETE FROM streams WHERE channel_id = ?").bind(id),
          env.DB.prepare("DELETE FROM channels WHERE id = ?").bind(id),
        ]);
            } else {
        await setChannelFields(env, ch, { enabled: 0 });
      }
      return ok(null);
    }

    if (method === "PUT") {
      let body: Record<string, unknown>;
      try {
        body = (await req.json()) as Record<string, unknown>;
      } catch {
        return fail(1001, "请求体不是合法 JSON");
      }
      await setChannelFields(env, ch, {
        name: typeof body.name === "string" ? body.name.slice(0, 128) : null,
        group: typeof body.group === "string" ? body.group.slice(0, 64) : null,
        enabled: typeof body.enabled === "boolean" ? (body.enabled ? 1 : 0) : null,
        number: Number.isFinite(Number(body.number)) ? Math.trunc(Number(body.number)) : null,
      });
      return ok({ id });
    }
  }

  const chStreams = /^\/api\/v1\/channels\/(\d+)\/streams$/.exec(path);
  if (chStreams) {
    const id = Number(chStreams[1]);
    if (method === "POST") return compatAddStream(req, env, id);
    return compatStreams(env, id);
  }

  const chStream = /^\/api\/v1\/channels\/(\d+)\/streams\/(\d+)$/.exec(path);
  if (chStream && method === "DELETE") {
    await env.DB.prepare("DELETE FROM streams WHERE id = ? AND channel_id = ?")
      .bind(Number(chStream[2]), Number(chStream[1]))
      .run();
      return ok(null);
  }

  if (/^\/api\/v1\/channels\/\d+\/epg$/.test(path)) {
    // 频道 ↔ 节目单的手工映射：边缘版的节目单是 CI 按频道名自动匹配的，
    // 没有"把某个台绑到某个 xmltv id"的操作。
    return notSupported("频道与节目单的手工映射（边缘的节目单由 CI 按频道名自动匹配）");
  }

  if (/^\/api\/v1\/channels\/\d+\/logo$/.test(path)) {
    // 没有对象存储，台标只能是外链
    return notSupported("上传/抓取台标（边缘模式没有图片存储，台标用源里的外链）");
  }

  // devices
  if (path === "/api/v1/devices") return compatDevices(env, url);
  if (path === "/api/v1/devices/bindcode") {
    return notSupported("生成绑定码（边缘模式的绑定码就是 BIND_CODE，不临时签发）");
  }
  const devId = /^\/api\/v1\/devices\/(\d+)$/.exec(path);
  if (devId && method === "DELETE") {
    await env.DB.prepare("DELETE FROM devices WHERE id = ?").bind(Number(devId[1])).run();
    return ok(null);
  }
  const devCfg = /^\/api\/v1\/devices\/(\d+)\/config$/.exec(path);
  if (devCfg && method === "PUT") return compatDeviceConfig(req, env, Number(devCfg[1]));

  if (/^\/api\/v1\/devices\/\d+\/(approve|reject)$/.test(path)) {
    return notSupported("设备审批（边缘模式没有审批流程：绑定码对了就是已绑定）");
  }

  // system
  if (path === "/api/v1/system/me") return ok(EDGE_ME);  if (path === "/api/v1/system/info") return compatSystemInfo(env);
  if (path === "/api/v1/system/config") {
    return notSupported("系统配置（边缘部署的配置在 wrangler.toml 和 Secrets 里）");
  }
  if (path === "/api/v1/system/logs") return ok([]);

  // epg：节目单由 CI 从源的 x-tvg-url 拉取并解析，这里只读
  if (path.startsWith("/api/v1/epg/")) {
    if (path === "/api/v1/epg/channels") {
      const { results } = await env.DB.prepare(
        `SELECT xmltv_id AS id, COUNT(*) AS program_count
           FROM epg_programs GROUP BY xmltv_id ORDER BY program_count DESC`,
      ).all();
      return ok(results ?? []);
    }

    if (path === "/api/v1/epg/stats") {
      const row = await env.DB.prepare(
        `SELECT COUNT(DISTINCT xmltv_id) AS channels, COUNT(*) AS programs,
                MIN(start_at) AS earliest_start, MAX(end_at) AS latest_end
           FROM epg_programs`,
      ).first<{
        channels: number;
        programs: number;
        earliest_start: number | null;
        latest_end: number | null;
      }>();

      const sync = await env.DB.prepare("SELECT v FROM kv WHERE k = 'last_epg_at'").first<{
        v: string;
      }>();

      const earliest = row?.earliest_start ?? 0;
      const latest = row?.latest_end ?? 0;
      return ok({
        sources: 0,
        enabled_sources: 0,
        channels: row?.channels ?? 0,
        programs: row?.programs ?? 0,
        earliest_start: earliest,
        latest_end: latest,
        covered_days: earliest && latest ? Math.ceil((latest - earliest) / 86400) : 0,
        last_sync: Number(sync?.v ?? 0) || 0,
        db_bytes: 0,
        keep_days: 3,
      });
    }

    if (path === "/api/v1/epg/search") {
      const q = (url.searchParams.get("q") || "").trim();
      if (!q) return ok([]);
      const { results } = await env.DB.prepare(
        `SELECT p.channel_id AS epg_channel_id, p.xmltv_id,
                COALESCE(o.name, c.name) AS channel_name,
                p.title, p.start_at, p.end_at, p.category
           FROM epg_programs p
           JOIN channels c ON c.id = p.channel_id
           LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key
          WHERE p.title LIKE ?
          ORDER BY p.start_at LIMIT 100`,
      )
        .bind(`%${q}%`)
        .all();
      return ok(results ?? []);
    }

    if (path === "/api/v1/epg/sources" || path === "/api/v1/epg/mapping") return ok([]);

    return notSupported("EPG 导入与映射（节目单由 CI 从源的 x-tvg-url 自动拉取）");
  }

  return null;
}

// ---------------------------------------------------------------- 播放列表

/**
 * 运行时生成 playlist.m3u（不再用构建时写死的静态文件）。
 *
 * 为什么必须运行时生成：屏蔽规则要在查询时生效，否则"解除屏蔽"之后
 * 静态文件里还是没有那些台，必须等一次重建 —— 而库里的频道数据是保留的
 * （见 scripts/build.mjs），所以这里能立刻反映出来。
 *
 * 每台只取 priority 最小的一条线路，和静态版本一致。
 */
async function renderPlaylistM3U(env: Env): Promise<string> {
  const { results } = await env.DB.prepare(
    `SELECT COALESCE(o.name, c.name)        AS name,
            COALESCE(o.logo, c.logo)        AS logo,
            c.tvg_id                        AS tvg_id,
            COALESCE(o.group_name, c."group") AS grp,
            s.url                           AS url,
            s.ua                            AS ua,
            s.headers                       AS headers
       FROM channels c
       LEFT JOIN channel_overrides o ON o.stable_key = c.stable_key
       JOIN streams s ON s.channel_id = c.id
      WHERE COALESCE(o.enabled, c.enabled) = 1
        AND COALESCE(o.group_name, c."group") NOT IN (
              SELECT value FROM json_each(
                CASE WHEN json_valid((SELECT v FROM kv WHERE k = 'blocked'))
                     THEN (SELECT v FROM kv WHERE k = 'blocked') ELSE '{}' END,
                '$.groups'))
        AND COALESCE(o.name, c.name) NOT IN (
              SELECT value FROM json_each(
                CASE WHEN json_valid((SELECT v FROM kv WHERE k = 'blocked'))
                     THEN (SELECT v FROM kv WHERE k = 'blocked') ELSE '{}' END,
                '$.channels'))
        AND s.priority = (SELECT MIN(s2.priority) FROM streams s2 WHERE s2.channel_id = c.id)
      ORDER BY c.sort, c.number, c.id`,
  ).all<{ name: string; logo: string; tvg_id: string; grp: string; url: string; ua: string; headers: string }>();

  const q = (v: string) => String(v ?? "").replace(/"/g, "'");
  const out: string[] = ["#EXTM3U"];
  for (const r of results ?? []) {
    const attrs: string[] = [];
    if (r.tvg_id) attrs.push(`tvg-id="${q(r.tvg_id)}"`);
    if (r.logo) attrs.push(`tvg-logo="${q(r.logo)}"`);
    attrs.push(`group-title="${q(r.grp)}"`);
    out.push(`#EXTINF:-1 ${attrs.join(" ")},${r.name}`);
    if (r.ua) out.push(`#EXTVLCOPT:http-user-agent=${r.ua}`);
    try {
      const h = JSON.parse(r.headers || "{}") as Record<string, string>;
      const ref = h.Referer || h.referer;
      if (ref) out.push(`#EXTVLCOPT:http-referrer=${ref}`);
    } catch {
      /* headers 坏了就只给地址 */
    }
    out.push(r.url);
  }
  return out.join("\n") + "\n";
}

// ---------------------------------------------------------------- 路由

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    const path = url.pathname;
    const method = req.method.toUpperCase();

    // ---- 无需认证 ----
    if (path === "/api/v1/health") return health(env);
    if (path === "/api/v1/device/register" && method === "POST") {
      return deviceRegister(req, env);
    }
    if (path === "/api/v1/admin/login" && method === "POST") {
      return adminLogin(req, env);
    }
    // 自带管理台的登录接口（admin/src/views/LoginView.vue 调的是 auth/login）
    if (path === "/api/v1/auth/login" && method === "POST") return adminLogin(req, env);
    if (path === "/api/v1/auth/logout") return adminLogout(req, env);

    // ---- 播放列表：里面是上游地址，必须和接口同等级保护 ----
    if (path === "/playlist.m3u") {
      const expected = env.BIND_CODE || "";
      if (!expected) return new Response("Not Found", { status: 404 });

      // 这里以前完全不限速：6 位口令可以随便猜，猜中就能拿到全部上游地址。
      // 用独立的桶，避免猜 playlist 把设备绑定/管理台登录一起锁掉。
      //
      // 注意是「先读、错了才写」：正常请求不写库。播放列表是客户端自动轮询的，
      // 用 bumpAttempt（每次必写一行再删一行）会把 D1 的每日写入额度吃掉——
      // 免费版是按行计费的，几台设备几分钟拉一次就上千行。
      const bucket = `playlist:${clientIp(req)}`;
      const fails = await currentFailures(env, bucket, PLAYLIST_WINDOW_SEC);
      if (fails >= PLAYLIST_MAX_FAILS) return new Response("Not Found", { status: 404 });

      const provided = url.searchParams.get("t") ?? "";
      if (!safeEqual(provided, expected)) {
        // 猜错才计数（原子 UPSERT）；用 404 而不是 401：不告诉对方这里确实有东西
        await bumpAttempt(env, bucket, PLAYLIST_WINDOW_SEC);
        return new Response("Not Found", { status: 404 });
      }
      // 成功时把「之前猜错的计数」清掉。没有行时 DELETE 影响 0 行，不产生写入。
      await clearFailures(env, bucket);
      // 运行时从 D1 生成：屏蔽/解除立刻生效，不等重建
      const body = await renderPlaylistM3U(env);
      return new Response(body, {
        status: 200,
        headers: {
          "content-type": "audio/x-mpegurl; charset=utf-8",
          "cache-control": "no-store",
        },
      });
    }

    // ---- 其余静态文件交给 assets ----
    if (!path.startsWith("/api/")) {
      // 配了 ADMIN_PATH 时，只有那一个入口能拿到首页；其余默认入口一律 404
      const entry = await adminEntry(req, env, path);
      if (entry) return entry;
      return env.ASSETS.fetch(req);
    }

    // ---- 管理台接口 ----
    if (path.startsWith("/api/v1/admin/")) {
      if (!(await authAdmin(req, env))) return failAdminUnauthorized();
      // 写操作推进版本号，但只限会改频道表的那些（见 affectsChannelList）
      if (method !== "GET" && affectsChannelList(path)) await bumpChannelsVersion(env);

      if (path === "/api/v1/admin/stats") return adminStats(env);
      if (path === "/api/v1/admin/settings") return adminSettings(env, url);
      if (path === "/api/v1/admin/build") return adminBuildInfo(env);
      if (path === "/api/v1/admin/rebuild") return adminRebuild(env, method);

      if (path === "/api/v1/admin/blocked") {
        if (method === "POST") return adminAddBlocked(req, env);
        if (method === "DELETE") return adminDelBlocked(env, url);
        return adminBlockedInfo(env);
      }
      if (path === "/api/v1/admin/channels") return adminChannels(env, url);
      if (path === "/api/v1/admin/streams") return adminStreams(env, url);
      if (path === "/api/v1/admin/devices") return adminDevices(env);

      if (path === "/api/v1/admin/sources") {
        if (method === "POST") return adminAddSource(req, env);
        return adminSources(env);
      }

      const srcMatch = /^\/api\/v1\/admin\/source\/(\d+)$/.exec(path);
      if (srcMatch) {
        const id = Number(srcMatch[1]);
        if (method === "PATCH" || method === "PUT") return adminUpdateSource(req, env, id);
        if (method === "DELETE") return adminDeleteSource(env, id);
      }

      const srcTest = /^\/api\/v1\/admin\/source\/(\d+)\/test$/.exec(path);
      if (srcTest && method === "POST") return adminTestSource(env, Number(srcTest[1]));

      if (path === "/api/v1/admin/stream" && method === "POST") {
        return adminAddStream(req, env);
      }

      const chMatch = /^\/api\/v1\/admin\/channel\/(\d+)$/.exec(path);
      if (chMatch) {
        const id = Number(chMatch[1]);
        if (method === "PATCH" || method === "PUT") return adminUpdateChannel(req, env, id);
        if (method === "DELETE") return adminDeleteChannel(env, id);
      }

      const stMatch = /^\/api\/v1\/admin\/stream\/(\d+)$/.exec(path);
      if (stMatch) {
        const id = Number(stMatch[1]);
        if (method === "DELETE") return adminDeleteStream(env, id);
      }

      const probeMatch = /^\/api\/v1\/admin\/stream\/(\d+)\/probe$/.exec(path);
      if (probeMatch && method === "POST") {
        return adminProbeStream(env, Number(probeMatch[1]));
      }

      const devMatch = /^\/api\/v1\/admin\/device\/(\d+)$/.exec(path);
      if (devMatch && method === "DELETE") {
        return adminDeleteDevice(env, Number(devMatch[1]));
      }

      return fail(2004, "没有这个管理接口");
    }

    // ---- 自带管理台（LITV2 的 Vue 应用）----
    // 它调的是 /api/v1/{sources,channels,groups,devices,system,epg}。
    if (/^\/api\/v1\/(sources|channels|groups|devices|system|epg|blocked)(\/|$)/.test(path)) {
      if (!(await authAdmin(req, env))) return failAdminUnauthorized();
      // 同上：自带管理台的写请求也在这里推进版本号
      if (method !== "GET" && affectsChannelList(path)) await bumpChannelsVersion(env);
      const r = await compatAdmin(req, env, path, method, url);
      return r ?? fail(2004, "没有这个接口");
    }

    // ---- 以下都要设备 token ----
    const device = await authDevice(req, env);
    // 消息里带「未绑定」是有意的：客户端看到这个词会自动跳回绑定页，
    // 否则 token 失效时用户只会看到一个死界面。
    if (!device) return fail(2003, "设备未绑定或会话已失效，请重新绑定");

    if (path === "/api/v1/tv/bootstrap") return tvBootstrap(env, device);
    if (path === "/api/v1/tv/channels") return tvChannels(env, url);
    if (path === "/api/v1/tv/heartbeat") return tvHeartbeat(req, env, device);
    if (path === "/api/v1/tv/resume") return tvResume(env, device);
    if (path === "/api/v1/tv/favorite" && method === "POST") {
      return tvFavorite(req, env, device);
    }
    if (path === "/api/v1/tv/progress" && method === "POST") {
      return tvSaveProgress(req, env, device);
    }

    const playMatch = /^\/api\/v1\/tv\/play\/(\d+)$/.exec(path);
    if (playMatch) return tvPlay(env, Number(playMatch[1]), url);

    const streamsMatch = /^\/api\/v1\/tv\/streams\/(\d+)$/.exec(path);
    if (streamsMatch) return tvStreams(env, Number(streamsMatch[1]));

    const epgMatch = /^\/api\/v1\/tv\/epg\/(\d+)$/.exec(path);
    if (epgMatch) return tvEpg(env, Number(epgMatch[1]), url);

    // ---- 边缘模式做不到的：返回空/明确错误，让客户端自然降级 ----
    // 这几项都需要 NAS 上的媒体中转或局域网能力，边缘版没有对应物：
    //   时移/录制 → 要落盘；DLNA 投屏 → 要能访问家庭局域网；
    //   遥控中转 → 要长连接配对（Worker 没有 Durable Object 撑不住）。
    if (path.startsWith("/api/v1/tv/timeshift/")) {
      return ok({ enabled: false, available_seconds: 0, url: "" });
    }
    if (path === "/api/v1/tv/dlna/renderers") return ok({ total: 0, items: [] });
    if (path === "/api/v1/tv/remote/targets") return ok([]);

    // 播放上报 / 提醒 / 预约录制：边缘没有统计页、也没有能按时触发的调度，
    // 收下就好。客户端对这些失败是静默的，返回成功能让界面状态更干净。
    if (
      path === "/api/v1/tv/stats" ||
      path === "/api/v1/tv/reminder" ||
      path === "/api/v1/tv/record"
    ) {
      return ok(null);
    }

    return fail(2004, "边缘模式不支持这个接口");
  },
} satisfies ExportedHandler<Env>;
